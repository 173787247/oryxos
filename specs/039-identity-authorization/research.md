# Research: 企业身份与授权基座（Identity & Authorization）

**Feature**: 039-identity-authorization | **Date**: 2026-09-14

现状摸底已完成（`oryxos-web/security` 与 `oryxos-web/config` 逐文件、`oryxos-storage` 用户与会话三件套、`oryxos-core/policy` 既有策略层、`db/migration` 两 vendor 版本账、`specs/012/018/020/026` 既有裁决逐条，行号在案），并对本刀**阶段一已落地**的实现（`Principal`/`Role`/`Action`/`ResourceRef`/`AuthorizationService`/`RoleBasedAuthorizationServiceImpl`/`WebRbacProperties`/`RoleMappingProperties`/`AuthorizationConfig`/`PrincipalHolder`/`RbacEnforcer`）做了逐类核对——本文的命名与配置键一律以工作树实现为准，阶段二（角色落库、路径映射、拒绝审计落库、启动校验）为设计目标并在 tasks.md 中列出。技术上下文无 NEEDS CLARIFICATION；未定项已在 spec.md §Clarifications 显式标注为待裁决。

## R1 主体载体：不可变值对象 + 请求属性，绝不用 ThreadLocal

**Decision**: 统一主体 `io.oryxos.core.auth.Principal(Kind kind, String id, String displayName, Set<Role> roles)`；`Kind ∈ {USER, API_KEY, ANONYMOUS}`；`null` 归一为匿名（`ANONYMOUS_ID = "anonymous"`），角色集合在构造器里去重并冻结。Web 侧用 servlet 请求属性承载（`PrincipalHolder`，属性名 `io.oryxos.web.principal`），未认证读回 `Principal.anonymous()` 而非 `null`。

**Rationale**:
- 仓库既有两扇 Filter 分别把身份塌缩成 `username` 字符串与布尔值（`BasicAuthFilter.authenticatedBySession` 用 `.isPresent()` 丢掉用户名；`ApiKeyAuthFilter` 走 `apiKeyService.verify` 只拿 boolean），三条路径连主体都不共享——这正是 #462 缺的前提。
- 不用 ThreadLocal：servlet 容器复用工作线程，某条提前 return 的分支漏 `remove()` 时下一个请求会继承上一个请求的主体，在授权场景里这是**越权**而不是脏读。运行时链路继续沿用 `io.oryxos.core.agent.ToolExecutionContext` 既有 ThreadLocal 纪律（同步阻塞 + finally 清除），两条链路各自在自己的边界内正确。

**Alternatives considered**: ① 复用 `ToolExecutionContext` 加主体字段——被否：它表达的是「这一步替哪个 Agent 跑」，与「谁在请求」是两个维度，混进去会让运行时的 Agent 语义变得含糊；② 引 `spring-security-core` 的 `Authentication` 做载体——被否：012 已明确只用 `spring-security-crypto` 单 jar（`PasswordEncoderFactory.java:12-14` 与 `specs/012-web-auth/spec.md:15`），为一个载体引入另一半框架会撕开既定边界；③ Spring MVC `HandlerMethodArgumentResolver` 注入 `Principal`——被否：过滤器阶段（`/actuator/*` 与静态资源）拿不到 handler，收口会漏。

## R2 收口位置：两扇既有 Filter 内部，零新增 URL pattern

**Decision**: 授权调用放在 `ApiKeyAuthFilter.doFilterInternal` 的三处成功分支与 `BasicAuthFilter.doFilterInternal` 的两处成功分支内、`filterChain.doFilter` 之前；裁决逻辑单独成类 `io.oryxos.web.security.RbacEnforcer`（唯一实现）。`AuthFilterConfig` 的 `/admin/*` 与 `ApiKeyFilterConfig.PROTECTED_URL_PATTERNS`（`/api/v1/*`、`/api/v2/*`、`/actuator/*`）**一字不改**。

**Rationale**:
- `ApiKeyFilterConfig.java:24` 的数组是单点手维护的，其类注释(`:15-18`)明写「新增 API 版本时必须同步在此登记，否则该版本整棵子树匿名可达」。靠新增 pattern 做授权会把这条风险放大一倍：漏一行 = 授权层自己开了个洞，而且默认档下完全观测不到。
- 管理台 SPA 与 REST 同源同路径，控制台数据面本来就走 `/api/v1/**` 且 `ApiKeyAuthFilter` 接受 session cookie 作为凭据（018 FR-011），所以「一个门 + 一个强制点」天然同时覆盖 API 与管理台两条面。
- 强制点单独成类而不是复制进两个 Filter：两扇门是两套独立代码、独立配置、独立启动校验，各写一份裁决必然出现「一扇收紧、另一扇忘了收」的漂移。

**Alternatives considered**: ① 新增第三个 Filter（`/api/v1/*` + 独立授权链）——被否：与既有门重复注册同模式，order 与豁免判定会分裂成两处真相；② 换 `HandlerInterceptor`——被否：拦不到 filter 层能覆盖的非 handler 请求（静态资源、actuator），且与 012/018 的 filter 层定位不一致；③ 引 Spring Security `SecurityFilterChain` + `@PreAuthorize`——被否：012/018/020 三份 spec 一致记录「不引全套」，且会让本刀从「补一层判断」变成「换一套认证栈」，回归面不可控。

## R3 角色矩阵：EnumSet 逐级叠加 + Key 能力上限 + 角色不取并集

**Decision**: 矩阵集中一处（`RoleBasedAuthorizationServiceImpl` 的静态集合）：
- `VIEWER = {READ_WORKSPACE, READ_AUDIT}`
- `EDITOR = VIEWER + {RUN_AGENT, MANAGE_AGENTS, MANAGE_KNOWLEDGE, MANAGE_SKILLS, MANAGE_SESSIONS}`
- `ADMIN = EDITOR + {MANAGE_WORKSPACE, MANAGE_CHANNELS, MANAGE_POLICIES, MANAGE_MEMBERS}`
- `API_KEY 主体 = ADMIN − {MANAGE_MEMBERS, MANAGE_POLICIES}`（能力上限）
- 主体自身已带角色时以它为准；仅当主体未携带角色时才回落到按类别配置的默认档（`defaultUserRoles` / `defaultApiKeyRoles`）——**取并集是权限提升缺陷**，明确禁止。

**Rationale**: 逐级包含用 `EnumSet` 叠加，避免「给 EDITOR 加了能力却忘给 ADMIN」这种只在生产暴露的矩阵漂移；Key 是长期有效、可复制到任意环境的机器凭证，让它能改治理规则（策略、成员）等于「一把泄露的 Key 可以给攻击者自己发权限」；`ApiKeyAuthFilter` 同请求可同时带 session 与 Key，角色取并集会让低权限账号借同请求里的高权限 Key 提权。

**Alternatives considered**: ① 角色平铺三份独立清单——被否：矩阵漂移风险；② 角色继承链放数据库（可配置矩阵）——被否：本刀不引入「改权限定义本身」的运行时面，矩阵是代码常量、评审可直接通读，改矩阵走 PR；③ 每角色一个权限位字符串（`agent:read` 一类）——被否：词表收敛靠枚举，自由字符串会漏判且漏判默认放行。

## R4 实现落位：矩阵落 core，持久化面落 storage（与 020 范式的有意收窄）

**Decision**: `AuthorizationService` 接口与 `RoleBasedAuthorizationServiceImpl` 实现均落 `oryxos-core/policy`；角色列读取（`WebUserService.rolesOf`）与授权事件落库（`AuthzEventRecorder`）落 `oryxos-storage`。

**Rationale**: `ToolPolicyService` 之所以「接口在 core、实现在 storage」，是因为它的实现必须读库（`tool_policy_rules`）。本刀的角色矩阵是**零持久化依赖的纯函数**，放 core 才能让运行时不依赖 `oryxos-web` 就复用同一实现（#462 的验收要求）；持久化面按惯例留在 storage。这是一处**与立项输入（issue #462）的偏差**：立项描述倾向「实现落 storage」，本计划的裁决是「决策实现落 core、状态落 storage」，理由如上，请评审重点确认（见 spec.md §Clarifications 第 5 条：命名与落位待确认）。

**Alternatives considered**: ① 实现也落 storage（严格镜像 020）——被否：运行时要复用就得让 core 依赖 storage，违反依赖方向；② 拆成「core 接口 + core 纯函数基类 + storage 装配」——被否：为一条静态矩阵多一层抽象，收益为零。

## R5 开关关系：认证开 → 授权才有对象（默认档不锁死自己）

**Decision**: `oryxos.web.rbac.enabled` 默认 `false`；开启后 `RbacEnforcer` 只在**上游 Filter 认证成功**的分支被调用（两扇 Filter 在自身 `isEnabled()=false` 时最早 `return`，根本不会走到强制点）。因此「rbac 开 + 认证关」不会把单机零配置部署打成全网 403；该组合在启动期以明确报错/告警呈现（FR-012）。

**Rationale**: 与 012/018 的 flag 纪律一致（`WebAuthProperties.java:18`、`WebApiKeyProperties.java:16` 默认 `false`），018 SC-001 把「默认关时端点行为零变化」写成硬成功标准。若把 RBAC 裁决放到认证门之前，默认部署（两 flag 全关、请求全匿名）会被自己的授权层拦死——这是本类特性最容易踩的自锁。

**Alternatives considered**: ① 新增第三个独立 Filter 承载 RBAC——被否：等于给默认部署再加一个必须显式放行的门（R2 的同一理由）；② 让 RBAC 自己读认证开关来决定是否生效——被否：开关语义会分裂成「谁先读谁说了算」，且测试要覆盖更多组合；③ 把 `denyAnonymous` 默认设为 `false` 以求稳——被否：授权启用后匿名默认拒绝才是安全默认值，本刀保留 `true`，用「不调用即不裁决」保证默认档安全；④ 对「rbac 开 + apikey 未开」只 WARN 不拒启——被否：该组合下 RBAC 是**静默空转**（没有任何门产出主体），运维会以为授权已生效；本项目对「带病运行」的既有口径是启动即拒（`ClusterStartupCheck`、`AuthStartupCheck`），故取拒启 + 报错指路。

## R6 路径 → 动作映射：固定表 + 未登记 fail-closed + 启动期全覆盖校验

**Decision**: 在 `RequestActionResolver`（web/security）里以「资源族 × 方法」的固定表把请求映射为 `(Action, ResourceRef)`，完整表见 [contracts/authorization-contract.md](contracts/authorization-contract.md) §4；命中不了任何规则的路径**拒绝并留痕**（fail-closed）；`RbacStartupCheck` 在启动期枚举 `RequestMappingHandlerMapping` 中所有 `/api/v1/**`、`/api/v2/**` 端点，逐个过映射函数，出现「既未映射、也未登记为豁免」的路径即**拒绝启动并打印路径清单**。

**Rationale**: 手维护的映射表天然会漏（`PROTECTED_URL_PATTERNS` 就是同一形态的坑），但把「漏」从静默放行变成**启动即拒**，风险就从安全洞降级为部署时的显式失败；这条也是唯一能同时满足「不新增 URL pattern」与「新端点默认受控」的办法。

**Alternatives considered**: ① 白名单式只保护已知危险端点（其余默认放行）——被否：新增端点默认放行，等于把风险交给下一位作者的记性；② 全部端点默认要求 ADMIN——被否：VIEWER/EDITOR 立刻失去意义，与最小角色矩阵矛盾；③ 用注解（`@RequiresAction`）贴在 Controller 上——被否：两扇 Filter 在 DispatcherServlet 之前跑，注解要在 handler 层才可见，会形成「filter 判一遍、handler 再判一遍」的双决策点，违 FR-002。

## R7 拒绝审计：新表 `authz_events`，只写拒绝，写失败不改裁决

**Decision**: 新增 `authz_events`（V8）：主体类别、主体标识、动作、资源类型与标识、理由、请求方法与路径、时间、可空 `trace_id`（存在 MDC 时带上）；每次拒绝写一条，放行不写；写失败只记 ERROR 日志并继续按裁决返回 403。

**Rationale**:
- `tool_invocations`/`llm_calls` 的语义是工具调用与模型调用，一条被拒的 HTTP 请求不属于任何一类（018 的「审计口径」明确不为每次认证单独落表，但#462 的验收明确要求「拒绝有审计」，两者不冲突：认证不落、授权拒绝落）。
- 只写拒绝：放行按请求量级落库会造成写放大（SQLite 单写者），而放行的价值主要在指标而非逐条留痕。
- 写失败不改裁决是硬口径：**审计失败绝不把拒绝变成放行**；这与 `ToolExecutor.recordCompleted` 的 fail-open 同源（不因旁路失败影响主流程），但方向相反（这里是「拒绝照旧」而非「允许照旧」）。

**Alternatives considered**: ① 复用 `tool_invocations` 加 `blocked_by='rbac'`——被否：该表要求 `session_id`/`tool_name` 等语义字段，HTTP 请求被拒时它们不存在，塞空值会让审计查询口径变糊；② 只写结构化日志不落库——被否：`LoginAttemptService` 的内存态教训（重启即失、多副本各自为政）就在眼前；③ 放行也落库——被否：写放大且无增量价值。

## R8 拒绝语义：401 归认证、403 归授权、无权与不存在不可区分

**Decision**: 授权拒绝返回 `403` + `ApiResponse` 统一信封与人话理由；认证失败仍由既有门产出原样的 `401`（`ApiKeyAuthFilter.reject` 的 `WWW-Authenticate: Bearer realm="OryxOS"`、`BasicAuthFilter` 的浏览器 302 / curl 401 一字不改）；403 响应体不含「资源是否存在」的区分信息。

**Rationale**: 混用状态码会让前端与调用方无法区分「补凭证」与「要权限」，也会让 018 既有的 401 契约测试失效；防枚举口径沿用 018 FR-004（失败原因只进服务端日志/审计）。

**Alternatives considered**: ① 统一 401 —— 被否：语义错误，会让调用方误以为要重新认证；② 统一 404 隐藏资源 —— 被否：与「拒绝要明确可解释」冲突，且会掩盖配置错误。

## R9 角色来源与赋值面：先落库，过渡期用显式配置默认档

**Decision**: 目标形态是「角色落 `web_users.roles`（V8）+ 角色赋值走 CLI」（`oryxos user role <username> <VIEWER|EDITOR|ADMIN>`，镜像既有 `user` 子命令骨架，`UserCommand` 内新增 leaf；`oryxos user list` 输出增加 ROLE 列）；过渡形态是本阶段工作树已落地的 `oryxos.web.rbac.roles.default-user-roles`（默认 `ADMIN`）与 `default-api-key-roles`（默认空），仅在主体**未自带角色**时兜底。落库落地后该键应收紧为空（解析不到角色即拒绝），并由启动期「无 ADMIN 账号即拒启」兜住锁死风险。管理台页面不放开角色管理。

**Rationale**:
- 仓库今天**没有** `/api/v1/users` REST 端点，账号管理是 CLI-only（`UserCommand` 的 add/list/delete/passwd/disable/enable），018 也把 Key 管理定为 CLI-only；角色赋值沿用同一面最省事、也最少暴露面。
- 没有赋值面时，`default-user-roles` 的默认 `ADMIN` 会让**所有管理台账号都是 ADMIN**——三档矩阵只对 API Key 生效，US1 的「VIEWER 只读 / EDITOR 干活」在任何真实请求上都观测不到。因此角色落库不是「锦上添花」，而是本刀 US1 成立的前提（见 spec.md §Clarifications「待补」条）。
- 默认 `ADMIN` 的原始动机（单机部署升级后不被自己的授权层锁死）在落库后由「列默认 `VIEWER` + 启动期无 ADMIN 即拒启 + CLI 赋值」三件套接管，既不失安全也不锁死。

**Alternatives considered**: ① 只做配置默认档、完全不落库（最省事）——被否：矩阵对管理台账号失效，特性退化成「Key 被拒」；② 管理台放开角色管理页面——被否：超出本刀范围且无前端测试基座；③ 直接在库里改 `roles` 列（无命令）——被否：与「不静默失败」「清晰报错」的运维口径不符；④ 首个账号自动 ADMIN——被否：隐式提权规则，评审必拒。

## R10 迁移双轨：V8 = SQLite JavaMigration + PG 同号 SQL

**Decision**: SQLite `WebUserRolesMigration extends BaseSqliteMigration`（`super("8", "web user roles")`），用 `columns(connection, "web_users")` 探测后 `ALTER TABLE web_users ADD COLUMN roles VARCHAR(255) NOT NULL DEFAULT 'VIEWER'`，再用 `execute` 建 `authz_events` 表；PostgreSQL 侧 `V8__web_user_roles.sql` 同版本号写等价 DDL；`SqliteMigrationsConfiguration` 增加 V8 Bean。

**Rationale**: SQLite 没有 `ADD COLUMN IF NOT EXISTS`，V6（`AgentRunColumnsMigration` + `postgresql/V6__agent_run_workbench.sql`）就是从零建表/加列走 JavaMigration、另一侧走 SQL 的现成先例；`BaseSqliteMigration` 已提供 `columns()`/`execute()` 与固定 checksum；026 的 V6 撞号重编号 V7（commit `45dc652`）是版本号必须提前协调的教训，本刀取 V8 并在 PR 里点名。

**Alternatives considered**: ① 两 vendor 都写纯 SQL——被否：SQLite 侧重复执行会报 duplicate column；② 新建独立角色关联表（`web_user_roles`）而不加列——被否：本刀只有三档且「一用户一档」是主流形态，关联表把最小刀做成了多对多模型；扩到多角色时先改 spec（`Principal.roles` 已是集合，接口无需改动）；③ 用 `hibernate.ddl-auto` —— 被否：宪法八与 `application.yml:29` 明令 `none`。

## R11 兼容边界：五条既有契约零变化

**Decision**: ① `/admin/**` 的注册模式、登录页与 `/admin/assets/**` 放行、session 有效即通过（不查 `web_users.enabled`）全部维持 012 现状；② 018 的豁免清单（`OPTIONS`、`/api/v1/health`、`/actuator/health*`、`/api/v1/auth/**`）逐条不变；③ 020 的工具策略与沙箱「策略放行不豁免沙箱」正交关系不变，本刀的 `Action`/`Principal` 与 `ToolPolicyService` 的主体（Agent 名）不互相替代；④ `tool_invocations`/`llm_calls` 的写入口径与列零变化；⑤ 不引 Spring Security 全套，`spring-security-crypto` 单 jar 的边界不变。

**Rationale**: 这些边界都有已记录的裁决出处（`specs/012-web-auth/spec.md:15/160`、`specs/018-rest-api-key/spec.md:9/126`、`specs/020-tool-policy/contracts/policy-api.md` §1.4、`PasswordEncoderFactory.java:12-14`）。spec-only PR 不跑 CI（`**.md` 在 `paths-ignore` 里），评审的闸门是人对宪法与既有裁决的逐条核对，把边界显式写出来是让评审能一眼核对的唯一办法。

**Alternatives considered**: ① 顺手把 session token 改成 JWT —— 被否：12 小时会话与撤权延迟的既有取舍要单独裁，不在本刀；② 顺手统一两扇 Filter 的 401 文案 —— 被否：会动 018/012 的既有契约测试，回归面无收益。

## 外部参考

- NIST RBAC 参考模型（角色层级 `⊆` 与最小组件集）：<https://csrc.nist.gov/projects/role-based-access-control>（调研日期 2026-09-14）
- OWASP Authorization Cheat Sheet（默认拒绝、集中裁决、不泄露存在性）：<https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html>（调研日期 2026-09-14）
- 项目内既有裁决出处：`specs/012-web-auth/spec.md:15,160`、`specs/018-rest-api-key/spec.md:9,126`、`specs/020-tool-policy/spec.md:123`、`specs/026-session-ownership/data-model.md`（Flyway 版本账）
