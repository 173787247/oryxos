# Research: REST API Key 认证

**Feature**: 018-rest-api-key | **Date**: 2026-08-26

技术上下文无 NEEDS CLARIFICATION；本文记录关键技术选型的裁决与理由，全部基于对 012-web-auth 既有实现的实地摸底（`BasicAuthFilter`、`WebAuthProperties`、`AuthFilterConfig`、`AuthStartupCheck`、`UserCommand`、`WebUserService`、`schema.sql`）。

## R1. Key 格式与哈希算法

**Decision**: Key 明文 = 固定前缀 `oryx_` + 42 位 base62 随机串（`SecureRandom`，约 250 bit 熵）。持久化存 SHA-256 十六进制哈希；`key_prefix` 列存 `oryx_` + 随机部分前 8 位，供 list/日志对账。

**Rationale**:
- 固定前缀让密钥扫描工具（secret scanning）可识别，符合业界惯例（GitHub `ghp_`、Stripe `sk_` 同型）。
- 哈希选 SHA-256 而非 BCrypt：BCrypt 的慢哈希是为低熵人类密码设计的，对 250 bit 高熵随机 Key 无增益，反而每请求 ~100ms 的代价直接击穿 SC-003（认证开销无感）。GitHub/Stripe 对 token 同样用快哈希。
- 012 的 `WebUserService` 用 BCrypt 是对的（人类密码），两者口径不同，各自正确。

**Alternatives considered**: BCrypt（否——性能不可接受且无安全增益）；明文存储+库加密（否——违反 spec FR-007 与宪法 VI）。

## R2. 校验查找与恒定时间比较（FR-010）

**Decision**: 收到请求先对明文 Key 计算 SHA-256，再按哈希值查库（`key_hash` 唯一索引），命中后用 `MessageDigest.isEqual` 复核。

**Rationale**: 攻击者无法通过构造输入探测哈希值的逐字节差异——输入先经 SHA-256 单向变换，数据库索引比较的计时信息不构成可利用的逐位 oracle（业界 token 校验标准做法）。`MessageDigest.isEqual` 兜底恒定时间复核。不存在的 Key 与已吊销的 Key 走同一失败路径、同一响应（防探测，FR-004/Edge Case）。

**Alternatives considered**: 按 `key_prefix` 查候选再逐一恒时比较（否——多此一举，前缀仅 8 位随机会撞车）；全表扫描恒时比较（否——O(n) 无必要）。

## R3. Filter 挂载方式与豁免清单

**Decision**: 新建 `ApiKeyAuthFilter extends OncePerRequestFilter`，经 `FilterRegistrationBean` 以 `addUrlPatterns("/api/v1/*")` 注册（镜像 012 的 `AuthFilterConfig` 模式）。豁免在 filter 内判定：`/api/v1/health`、`/api/v1/auth/` 前缀、HTTP `OPTIONS` 方法（CORS 预检，FR-014）。

**Rationale**: 与 `BasicAuthFilter`（只拦 `/admin/*`）URL 模式互不重叠，两个 filter 各管一扇门、互不感知，`/admin/**` 天然不受影响（FR-002）。豁免清单短且稳定，放 filter 内白名单即可，无需可配置化（YAGNI）。

**Alternatives considered**: 引 Spring Security 全套（否——012 已裁决只用 spring-security-crypto，全套对两条路径的需求过重）；拦截器 HandlerInterceptor（否——filter 在 DispatcherServlet 之前，与 012 同层，且能覆盖非 handler 请求）。

## R4. 管理台 session 互认（FR-011，Clarifications Q1）

**Decision**: `ApiKeyAuthFilter` 在 Key 校验失败/缺失时，读取 `oryxos_session` cookie → `WebSessionService.findValid`，有效即放行。复用 `BasicAuthFilter.authenticatedBySession` 同款逻辑（cookie 名常量提为共享）。

**Rationale**: `WebSessionService` 在 oryxos-storage，oryxos-web 已依赖，零新增件；session 校验是一次索引查询，不增可感知延迟。

## R5. 存储与 schema

**Decision**: `api_keys` 新表进 `oryxos-storage/src/main/resources/schema.sql`（`CREATE TABLE IF NOT EXISTS`，新表非 ALTER，存量库无迁移风险，FR-013）；JPA 实体 `ApiKey` + `ApiKeyRepository` + `ApiKeyService`（镜像 `WebUser` 三件套，业务逻辑收在 service：生成、校验、吊销、盘点）。

**Rationale**: 与 012 的 `web_users`/`web_sessions` 完全同构；无需新的 SchemaUpgrade 类（那是给 ALTER 场景用的）。

## R6. last_used_at 更新节流（FR-009）

**Decision**: 校验通过后在同一请求线程内同步更新 `last_used_at`（宪法 VII：不引入异步编程模型），并做内存节流：同一把 Key 距上次落库不足 60 秒则跳过。更新失败只记日志，不阻断请求。

**Rationale**: SQLite 单写者，逐请求写会造成写放大并与业务写竞争；「最近使用时间」是治理信号，分钟级精度足够。内存节流表是缓存不是状态，重启丢失无害（宪法 VIII 不冲突）。

## R7. 配置与启动校验（FR-001/FR-012，Clarifications Q2）

**Decision**: 新建 `WebApiKeyProperties`（prefix `oryxos.web.apikey`，仅 `enabled`，默认 false）。新建 `ApiKeyStartupCheck implements ApplicationRunner` + `@ConditionalOnWebApplication(SERVLET)`（镜像 `AuthStartupCheck`），但两种异常组合都只 `LOG.warn` 不抛异常：① enabled 但无有效 Key；② enabled 但 `oryxos.web.auth.enabled=false`。

**Rationale**: 与 `AuthStartupCheck` 的差异是有意的——012 无账号开 auth 等于永久锁死管理台故 fail-fast；018 无 Key 开门禁是「全拒」的安全状态而非坏状态，且 CLI 生成 Key 不依赖 serve 存活，告警即可（Clarifications Q2 裁决）。`SERVLET` 条件同样必要：`oryxos apikey add` 用 `WebApplicationType.NONE` 起 Spring，不能被 web 侧检查波及。

## R8. CLI 子命令

**Decision**: 新建 `ApiKeyCommand`（`oryxos apikey`，子命令 add/list/revoke），完整镜像 `UserCommand` 骨架：每个 leaf 自起一次性 Spring 上下文（`WebApplicationType.NONE`），`context.getBean(ApiKeyService.class)` 干活。`add` 成功后打印明文 Key 一次并附「仅显示这一次」警告；`list` 输出 NAME / PREFIX / STATUS / CREATED_AT / LAST_USED_AT。

**Rationale**: 与 `oryxos user` 心智模型一致（FR-005 明确要求）；明文只经 stdout、不落日志。

## R9. 401 响应形态

**Decision**: 401 + 统一信封 `ApiResponse.error(401, "Unauthorized")` + `WWW-Authenticate: Bearer realm="OryxOS"`。所有失败原因（无 Key/格式错/不存在/已吊销）同一响应；具体原因仅 DEBUG 级日志且只记 Key 前缀。

**Rationale**: 与 `BasicAuthFilter` 的 401 口径一致（信封复用）；`Bearer` 挑战头符合 RFC 6750，成本为零。防探测与防明文入日志由同一响应 + 前缀脱敏保障（FR-004/FR-006）。
