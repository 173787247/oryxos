# Implementation Plan: REST API Key 认证

**Branch**: `018-rest-api-key` | **Date**: 2026-08-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/018-rest-api-key/spec.md`

## Summary

为 `/api/v1/**` 加 API Key 机器调用认证，补齐 v0.2「把门锁上」缺角。技术路线：完整镜像 012-web-auth 的既有模式——`FilterRegistrationBean` 挂 `ApiKeyAuthFilter`（只拦 `/api/v1/*`，与 012 的 `/admin/*` filter 互不重叠）、`WebApiKeyProperties` feature flag 默认关、`api_keys` 新表进 schema.sql（`CREATE TABLE IF NOT EXISTS`，无迁移风险）、`ApiKeyService` 三件套进 oryxos-storage、`oryxos apikey` 子命令镜像 `UserCommand`。Key 为 `oryx_` 前缀高熵随机串，存 SHA-256 哈希，按哈希查找 + 恒时复核；管理台 session 互认复用 `WebSessionService`。零新依赖、零新模块。

## Technical Context

**Language/Version**: Java 21（virtual thread）

**Primary Dependencies**: Spring Boot 3.x（Spring MVC servlet filter）、Spring Data JPA、Picocli；零新增依赖（哈希用 JDK `MessageDigest`，随机用 `SecureRandom`）

**Storage**: SQLite 新表 `api_keys`（schema.sql 手工建表，新表非 ALTER）

**Testing**: JUnit 5 + MockMvc（filter 行为）+ 既有 E2E 模式（oryxos-boot）；`mvn verify` 全量质量门禁

**Target Platform**: Linux server（单 fat JAR，同现状）

**Project Type**: Maven 多模块单体——涉及 oryxos-storage（实体/服务）、oryxos-web（filter/配置/启动校验）、oryxos-cli（apikey 子命令）三个既有模块

**Performance Goals**: 认证开销对调用方无感（SC-003）：SHA-256 一次 + 索引查一次，微秒级；不用 BCrypt（见 research R1）

**Constraints**: flag 默认关回归零破坏（SC-001）；明文 Key 全链路只出现一次（SC-005）；恒定时间比较（FR-010）；同步阻塞模型（宪法 VII）

**Scale/Scope**: Key 数量个位数到几十把（每调用方一把）；三模块合计约 10 个新文件 + 2 个既有文件小改（schema.sql、CLI 主入口注册子命令）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 评估 | 结论 |
|------|------|------|
| I 自实现 ReAct | 不涉及（纯 web 认证层） | ✅ |
| II Spring AI 边界 | 不涉及 | ✅ |
| III Provider 显式映射 | 不涉及 | ✅ |
| IV 目录=Agent / Skill 软连接 | 不涉及 | ✅ |
| V 审计 Day One 落库 | 两张审计表口径不变；Key 治理信号走 `last_used_at`（spec Assumptions 明确不为认证单独落审计表） | ✅ |
| VI 安全是地基 | Key 只存 SHA-256 哈希、明文仅生成时 stdout 一次、日志只记前缀、恒时比较防侧信道、失败响应防探测；不用 SecurityManager | ✅ |
| VII 同步 + 虚拟线程 | `OncePerRequestFilter` 同步阻塞；无 Reactor/CompletableFuture | ✅ |
| VIII 目录配置 / 状态外置 / 手工 schema | Key 持久化 SQLite；新表走 schema.sql `CREATE TABLE IF NOT EXISTS`（非 Hibernate 自动迁移）；last_used 节流表是内存缓存非状态，重启丢失无害 | ✅ |
| 模块约束 | 不新建模块，不改 oryxos-core；storage/web/cli 各归其位，无循环依赖 | ✅ |

**Phase 1 设计后复评**: 设计未引入新违背项，全部通过。无需 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/018-rest-api-key/
├── plan.md              # 本文件
├── research.md          # Phase 0：9 项技术裁决（R1~R9）
├── data-model.md        # Phase 1：api_keys 表 + 生命周期
├── quickstart.md        # Phase 1：V1~V6 验收走查
├── contracts/
│   └── auth-contract.md # Phase 1：请求认证/CLI/配置契约
└── tasks.md             # Phase 2（/speckit-tasks 生成）
```

### Source Code (repository root)

```text
oryxos-storage/
├── src/main/java/io/oryxos/storage/
│   ├── ApiKey.java                      # 新增：JPA 实体
│   ├── ApiKeyRepository.java            # 新增：findByKeyHash/findByName/existsByName
│   └── ApiKeyService.java               # 新增：create/verify/revoke/list/hasActiveKey
├── src/main/resources/schema.sql        # 修改：追加 api_keys 表
└── src/test/java/io/oryxos/storage/
    └── ApiKeyServiceTest.java           # 新增：生成/校验/吊销/重名/哈希不可逆

oryxos-web/
├── src/main/java/io/oryxos/web/
│   ├── config/
│   │   ├── WebApiKeyProperties.java     # 新增：oryxos.web.apikey.enabled（默认 false）
│   │   └── ApiKeyFilterConfig.java      # 新增：FilterRegistrationBean，addUrlPatterns("/api/v1/*")
│   └── security/
│       ├── ApiKeyAuthFilter.java        # 新增：豁免判定 + Key 校验 + session 互认 + 401
│       └── ApiKeyStartupCheck.java      # 新增：两条 WARN（无 Key / auth 未开），不阻断
└── src/test/java/io/oryxos/web/security/
    └── ApiKeyAuthFilterTest.java        # 新增：路径裁决表全覆盖（MockMvc）

oryxos-cli/
└── src/main/java/io/oryxos/cli/command/
    └── ApiKeyCommand.java               # 新增：apikey add/list/revoke（镜像 UserCommand）
    # 修改：主入口 @Command subcommands 注册 ApiKeyCommand

oryxos-boot/
└── src/test/java/io/oryxos/boot/
    └── ApiKeyAuthE2ETest.java           # 新增：quickstart V1~V5 端到端（镜像 AuditDashboardE2ETest）
```

**Structure Decision**: 三个既有模块各归其位——凭证实体与校验逻辑在 oryxos-storage（与 `WebUserService` 并列，CLI 与 web 共用）；filter/配置/启动校验在 oryxos-web（与 012 的 `BasicAuthFilter` 并列）；CLI 子命令在 oryxos-cli。不新建模块、不碰 oryxos-core（认证是 web 边缘关注点，非跨模块契约）。`ApiKeyAuthFilter` 与 `BasicAuthFilter` 同包，package-private 的 `SESSION_COOKIE` 常量直接可见——012 代码零改动（比原计划的可见性调整更干净）。

## 关键设计裁决（详见 research.md）

| # | 裁决 | 要点 |
|---|------|------|
| R1 | Key 格式与哈希 | `oryx_`+42 位 base62（~250bit 熵）；SHA-256 非 BCrypt（高熵 Key 快哈希是业界标准，BCrypt 击穿 SC-003） |
| R2 | 校验查找 | 先 SHA-256 再按哈希索引查库 + `MessageDigest.isEqual` 复核；不存在/已吊销同路径同响应 |
| R3 | Filter 挂载 | `FilterRegistrationBean("/api/v1/*")`；豁免：health、auth 子树、OPTIONS；与 012 filter 互不重叠 |
| R4 | session 互认 | 复用 `WebSessionService.findValid`，零新增件 |
| R5 | 存储 | schema.sql 新表 + JPA 三件套镜像 WebUser；无需 SchemaUpgrade 类 |
| R6 | last_used 节流 | 60s 内存节流防 SQLite 写放大；更新失败不阻断 |
| R7 | 启动校验 | `ApplicationRunner` + `@ConditionalOnWebApplication(SERVLET)`；只 WARN 不抛（与 012 fail-fast 的差异有意，见 research） |
| R8 | CLI | 镜像 `UserCommand`：一次性 Spring 上下文 + `WebApplicationType.NONE` |
| R9 | 401 形态 | 统一信封 + `WWW-Authenticate: Bearer`；失败原因只进 DEBUG 日志且只记前缀 |
