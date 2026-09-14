# Feature Specification: 企业身份与授权基座（Identity & Authorization）

**Feature Branch**: `039-identity-authorization`

**Created**: 2026-09-14

**Status**: Draft

**Input**: User description: "企业身份与授权基座（039-identity-authorization）：epic #454「企业控制面：身份、授权与资产治理」子项 #462 的第一刀。现状（代码级审计在案）：两扇自研 Filter 各自只回答「认证通过了吗」——BasicAuthFilter 只拦 /admin/*（AuthFilterConfig.java:35），ApiKeyAuthFilter 只拦 /api/v1/*、/api/v2/*、/actuator/*（ApiKeyFilterConfig.java:24），两者都默认关；全仓零 SecurityFilterChain / 零 @PreAuthorize / 零角色概念；运行时链路（AgentService.process 全体重载）没有任何「代表谁执行」的载体；sessions.user_id 被控制台写成常量 'default'（SessionApiController.java:52/:76），不是可用主体源；api_keys 无 owner/scope（018 已明确留到 v1.0 租户模型）。于是 #462 的验收标准「API、管理台、运行时共用同一授权决策」缺少最基础的前提：三条路径连主体都不共享。要点：统一主体 Principal{kind,id,displayName,roles}（主体永远不是 null，匿名也是一等主体）；唯一决策点 AuthorizationService（接口在 oryxos-core/policy，与既有 ToolPolicyService 同包不撞名，裁决结果类型为 Decision）；最小角色矩阵 VIEWER ⊆ EDITOR ⊆ ADMIN（矩阵集中一处、可人工通读）；授权收口在两扇既有 Filter 内部、不新增 URL pattern、不引 Spring Security 全套；每请求解析角色不缓存（角色撤销不延迟）；拒绝一律 403 + 统一信封可读理由，MUST NOT 泄露资源是否存在；每次拒绝落 authz_events（V8 双轨迁移），放行不落库；feature flag 默认关、零行为变化。不做：OIDC/SSO 实现（#461）、资产 owner/版本/可见范围（#463）、会话所有权强制、三级租户模型、per-key scope、认证事件审计、Spring Security filter chain。"

> 编号说明：epic #454 立项时无 spec 编号；本刀取当时 `specs/` 最大号 038 顺延为 039（`.specify/scripts/bash/create-new-feature.sh --dry-run` 实测输出 `FEATURE_NUM=039`）。**027 是规划文档预留空号，不回填**（025 先例：脚本只取 max+1，缺口不补）。
>
> 前置：012-web-auth（管理台 Basic Auth + session）、018-rest-api-key（/api/v1 机器调用门禁）、020-tool-policy（Agent 级工具减法层）。本刀不替换其中任何一层，只在其之上补「谁能不能做这件事」。

## Clarifications

### Session 2026-09-14

**待 maintainer 裁决（3 项，均附推荐答案与理由，尚未拍板）**

- Q: RBAC 的隔离边界该用什么词？仓库里「Workspace」已被「Agent 工作区根目录」（`oryxos.root` / `.oryxos`）占用，直接拿来做租户边界会一词二义。 → **A（推荐）**：本刀**不引入新的顶层名词**，统一用「**边界 / boundary**」描述授权范围；`Action.MANAGE_WORKSPACE` 与 `ResourceRef.TYPE_WORKSPACE` 只是既有实现里已固化的动作与资源词表，其语义**限定为「本部署的授权范围整体」**，与「Agent 工作区根目录」无关（消歧段落见 §Edge Cases 与 contracts §1）。正式命名（Tenant / Org / Project）留待 #462 的租户模型裁决，届期只改文档与枚举文案、不改接口形状。理由：名词未定就落进接口，评审会把精力耗在命名而非权限边界上；且 `docs/DemandAnalysis.md:530` 的三级租户模型（组织/部门/项目）比任何单一名词都更可能定稿。**状态：待 maintainer 裁决。**
- Q: flag 关闭时 `/admin/**` 的既有语义是否原样保留？ → **A（推荐）**：原样保留，且 flag 开启时也不改动它——本刀**不新增任何 URL pattern、不改 `AuthFilterConfig` / `ApiKeyFilterConfig` 的注册模式**；`/admin/**` 继续只做认证（登录页与 `/admin/assets/**` 放行、session cookie 有效即通过、不查 `web_users.enabled`，全部维持 012 已记录裁决）；授权裁决只发生在 `/api/v1|v2/*`、`/actuator/*` 这条既有门内（控制台的数据面本来就走 `/api/v1/**`，见 018 的 session 即凭据设计）。理由：`ApiKeyFilterConfig.PROTECTED_URL_PATTERNS` 是**单个手维护数组**，其类注释明写「新增 API 版本时必须同步登记，否则该版本整棵子树匿名可达」——任何靠加 pattern 实现的授权都会把这个坑放大一倍。**状态：待 maintainer 裁决。**
- Q: API Key 的归属怎么定——挂到人或组织继承其权限，还是绑成 Agent 的委托身份？ → **A（推荐）**：本刀**不做归属建模**（`api_keys` 不加 `owner`/`scope` 列，018 已明确「按 Key 细分端点权限留到 v1.0 租户模型定型后」），API Key 主体**默认不给任何角色**（`oryxos.web.rbac.roles.default-api-key-roles` 默认空 = 一律拒绝，机器凭证不默认授权）；需要让机器调用方在授权开启后继续干活时，由部署方显式授予（例如 `[EDITOR]`），并叠加实现层已固化的**Key 能力上限**：即使显式给到 ADMIN，`MANAGE_MEMBERS` 与 `MANAGE_POLICIES` 也不放行（`RoleBasedAuthorizationServiceImpl.API_KEY_MAX_ACTIONS`）——不让一把可被复制到任意环境的长期凭证去改治理规则本身。理由：挂到人需要 `owner` 列 + 归属治理，与 #463 资产治理重叠且本刀不做；绑 Agent 会把主体与 020 的 Agent 级工具策略（那问的是「Agent 能不能用这个工具」）混为一谈，主体语义反而更糊。真要做到「Key 代表谁」，先做 #461 的身份映射。**状态：待 maintainer 裁决（安全默认值取「不给权限」，需要放行时靠显式配置而非隐式继承）。**

**待裁决（1 项，阻断 US1 可验收性）**

- Q: 路径 → 动作的映射边界怎么划（哪些端点算 `MANAGE_WORKSPACE` / `MANAGE_CHANNELS` / `READ_AUDIT`）？ → **A（推荐）**：按 [contracts/authorization-contract.md](contracts/authorization-contract.md) §4 的**全表**实现（资源族 × 方法），未登记路径一律拒绝并留痕，另加**启动期端点全覆盖校验**把「漏登记」从静默放行变成启动即拒。理由：当前强制切面只做基线判定（`RbacEnforcer.BASELINE_ACTION = READ_WORKSPACE`），矩阵里 EDITOR/ADMIN 的差异不会在任何真实请求上生效——「最小角色矩阵」会退化成「认证开关」，US1 的 403 场景无法成立。**状态：待 maintainer 裁决（本刀阻断项）。**

**待补（1 项，同属阻断 US1 验收的实现缺口）**

- Q: 角色从哪来——本刀要不要把角色落到 `web_users.roles`？ → **A（推荐）**：**要，且必须与路径映射同批落地**。当前工作树的口径是「角色不落库、主体统一回落到配置默认档」（`RoleMappingProperties` 的 `default-user-roles` 默认 `ADMIN`），这解决了「单机部署自锁」，但同时意味着**所有管理台账号都是 ADMIN**——三档矩阵只对 API Key 生效，US1 的「VIEWER 只读 / EDITOR 干活」在任何真实请求上都观测不到；再叠加基线判定，RBAC 的净效果只剩「Key 被拒」。落库方案见 data-model.md（V8：`web_users` 加 `roles` 列 + `authz_events` 表；SQLite 走 `JavaMigration` + `BaseSqliteMigration`），赋值面走 CLI（`oryxos user role`，对齐 018 的 CLI-only 先例），并把 `default-user-roles` 收紧为**空**（解析不到角色即拒绝）+ 启动期「无 ADMIN 账号即拒启」兜住锁死风险。若 maintainer 要求本刀继续收窄，则 US1 的验收场景需相应降级为「Key 主体拒绝 + 认证面零回归」。**状态：待 maintainer 裁决。**

**待确认（1 项，质量门禁口径）**

- Q: `RoleBasedAuthorizationServiceImpl` 的命名与落位与既有范式不一致，是否要在实现期调整？ → **A（推荐）**：（a）落位——角色矩阵是**零持久化依赖的纯函数**，放 `oryxos-core/policy` 可让运行时与 Web 复用同一实现；这与 `ToolPolicyService`（接口在 core、SQLite 实现在 storage）的依赖倒置范式**有意收窄**，持久化面（角色列读取、拒绝审计落库）仍落 `oryxos-storage`，本 spec 显式声明该偏差（评审最易追问处）。（b）命名——020 先例把实现类改名 `ToolPolicyServiceImpl` 以满足 P3C「Service 实现类必须以 `Impl` 结尾」；`RoleBasedAuthorizationServiceImpl` 若被 P3C 拦下，按同一先例改名（或去掉 `Service` 后缀），文档同步。**状态：待确认（以 `mvn verify` 的 P3C 结果为准）。**

**已裁决（本刀内部拍板，随 spec 生效）**

- Q: 认证失败与授权失败怎么区分？ → A: **401 归认证门**（012/018 既有响应体与挑战头逐字节不变），**403 归授权门**（统一 `ApiResponse` 信封 + 人话理由）。两者不混用，避免把「没带凭证」与「带了但不够」写成同一种失败。
- Q: 拒绝要不要写进既有两张审计表？ → A: **不写**。`tool_invocations` / `llm_calls` 的语义是「工具调用」「模型调用」，一条被拒的 HTTP 请求既不是工具也不是模型调用；新增 `authz_events` 专表（V8），**只落拒绝、放行不落库**（避免每请求一次写放大）。认证事件（登录/登出/Key 使用）仍不做，归 #461。
- Q: 角色从哪来、要不要缓存？ → A: 管理台账号的角色权威源是 `web_users.roles`（V8 新增列），**每请求解析一次、不缓存**——012 的 session 有效期默认 12 小时（`WebSessionService.findValid` 不查 `web_users.enabled`），若角色被缓存，撤权最长 12 小时才生效；角色落库落地前由配置默认档过渡（`oryxos.web.rbac.roles.*`），API Key 主体无库内归属、默认不给角色。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 按角色放行与拒绝（Priority: P1）

运维负责人要给团队分权：新人先只读，能看工作区清单、资产列表与审计报表，但看不进具体会话内容、也不能改任何东西；骨干能建 Agent、导知识库、管会话、跑会话；只有他自己能改渠道凭证、工具策略和成员角色。他在 CLI 里给三个账号分别赋上 VIEWER / EDITOR / ADMIN，打开 `oryxos.web.rbac.enabled` 重启。此后三个人各自登录管理台，能做的事完全按档位分开：越权操作稳稳地拿到 403 与人话理由，而不是「系统坏了」。不开这个开关的部署，行为与他升级之前一模一样。

**Why this priority**: 这一条就是 #462「共用同一授权决策」的可观测形态，也是唯一能单独演示、单独验收的行为面。没有它，统一主体与决策点只是结构空壳；有了它，OryxOS 才第一次从「谁能进门」走到「谁能碰什么」。

**Independent Test**: `oryxos user add` 建三个账号 → `oryxos user role` 分别赋 VIEWER / EDITOR / ADMIN → 开 `oryxos.web.apikey.enabled=true` 与 `oryxos.web.rbac.enabled=true` 重启 → 用三个账号各自的 session（或 Basic）打同一批端点：只读账号 GET `/api/v1/agents` 得 200、POST `/api/v1/agents` 得 403；骨干账号 POST `/api/v1/agents` 得 200、POST `/api/v1/tool-policy/rules` 得 403；管理员账号两者都得 200。再把 flag 关回去，「同批端点全部 200」与升级前一致。

**Acceptance Scenarios**:

1. **Given** `oryxos.web.rbac.enabled` 保持默认（关闭），**When** 任意角色账号（甚至未赋角色）调用任意既有端点，**Then** 行为与本特性之前完全一致（无 403、无新增必填配置、无审计写入）。
2. **Given** RBAC 与 API Key 门禁均已开启且账号角色为 VIEWER，**When** 该账号读工作区清单、资产清单与审计报表一类只读面，**Then** 放行；**When** 它尝试新建/删除 Agent、跑会话、读写会话、改渠道或策略，**Then** 一律 403 且响应体含可读理由。
3. **Given** 账号角色为 EDITOR，**When** 它建 Agent、导知识库、绑定 Skill、跑会话，**Then** 放行；**When** 它改渠道配置、工具策略或成员角色，**Then** 403（EDITOR 不得碰边界与治理面）。
4. **Given** 账号角色为 ADMIN，**When** 它执行矩阵内任一动作，**Then** 放行（含渠道、策略、成员）。
5. **Given** 一把 API Key 未获显式授权（`default-api-key-roles` 默认空），**When** 它请求任意受控端点，**Then** 403；**Given** 部署方显式授予它 `EDITOR`，**When** 它请求运行/资产面，**Then** 放行，**When** 它请求治理面（成员、工具策略），**Then** 403——即使显式配到 ADMIN 也不放行这两项。

---

### User Story 2 - 每次拒绝都说得清（Priority: P2）

被拦下来的运维要能自己回答三个问题：是谁被拦、想做什么、为什么不行。他在审计里按「拒绝」筛出记录，每行都带着主体类别与标识（账号名 / Key 名称，绝不含 Key 明文）、动作、资源、理由与时间；他自己登录管理台时，`/api/v1/auth/me` 会告诉他当前角色，页面被拒时看到的是统一信封里的一句话，而不是一片白屏。

**Why this priority**: 「拒绝行为明确且有审计」是 #462 的验收条款之一，也是本项目从第一天起就立的规矩（宪法五：审计 Day One 落库）。没有留痕，RBAC 在生产里只会被当成偶发故障；而拒绝若不可解释，运维第一反应是关掉开关——那这个特性就白做了。依赖 US1 的决策点先立起来。

**Independent Test**: 制造三类拒绝（未带凭证的匿名请求、VIEWER 越权写、API Key 触治理面）→ 查 `authz_events`：恰好三条、字段齐全（主体类别/主体标识/动作/资源/理由/时间），且**没有任何放行行**；把 `LOG` 级别调到默认，确认拒绝理由同时出现在服务端日志里；`GET /api/v1/auth/me` 返回当前用户的角色集合。

**Acceptance Scenarios**:

1. **Given** RBAC 已开启，**When** 一次请求被拒绝，**Then** 恰好一条 `authz_events` 记录落库，含主体类别与标识、动作、资源描述、可读理由与时间戳，且不含任何凭证明文。
2. **Given** 同一批请求里既有放行也有拒绝，**When** 查 `authz_events`，**Then** 只有拒绝行（放行不落库，零写放大）。
3. **Given** 已登录的管理台账号，**When** 调用 `GET /api/v1/auth/me`，**Then** 响应含当前角色集合与 RBAC 开关状态；未启用时该端点行为与 012 现状一致。
4. **Given** 拒绝审计写入失败（库不可写），**When** 该请求被裁决，**Then** 请求仍被拒绝（**审计失败绝不把拒绝变成放行**），服务端留下一条 ERROR 日志，且不影响后续请求。

---

### User Story 3 - 三条路径同一判决，且不打破既有体系（Priority: P3）

管理台浏览器、REST 调用方、以及未来要接进来的运行时执行链路，问的是同一个问题、拿的是同一份答案：`AuthorizationService` 只有一处实现、只有一套矩阵。同时，已经上线的东西一个都不能坏：`/admin/**` 的登录与静态资源照旧，018 的豁免清单（`/api/v1/health`、`/api/v1/auth/*`、`OPTIONS`）照旧，020 的工具策略与沙箱「策略放行不豁免沙箱」的正交关系照旧。

**Why this priority**: 价值在于「结构成立 + 不破坏」，属于回归保障而非新增能力，故排最后；但不满足它，前两个故事不能上生产。

**Independent Test**: 三开关全开下用浏览器走查管理台：登录 → 概览/Agent/会话页按登录账号的角色正常放行或给出 403 提示（无白屏、无 401 回归）；`curl` 无凭据打 `/api/v1/health` 与 `/api/v1/auth/login` 仍按 018 现状响应；`mvn verify` 全量门禁全绿；对照 `research.md` R11 的接线清单确认「运行时复用同一接口、不依赖 web 模块」。

**Acceptance Scenarios**:

1. **Given** 三开关全开，**When** 管理台 SPA 带有效 session 调用 `/api/v1/**` 数据接口，**Then** 一律经同一决策点裁决（放行或 403），不存在第二套判定；`/admin/**` 的登录页与静态资源行为与 012 现状完全一致。
2. **Given** 任一开关组合，**When** 无凭据请求 `/api/v1/health`、`/api/v1/auth/*` 或发送 `OPTIONS` 预检，**Then** 豁免清单与 018 验收时逐条一致。
3. **Given** flag 关闭（默认档），**When** 跑全量既有测试，**Then** 零改动全绿（`Principal`/`Decision` 的存在不改变任何既有断言），且 `authorizationService` 注入的是 `AuthorizationService.ALLOW_ALL`（零破坏锚点可断言）。
4. **Given** 运行时执行链路（ReAct 循环 / ToolExecutor），**When** 后续特性要复用授权，**Then** 它只依赖 `oryxos-core` 的接口、不反向依赖 `oryxos-web`；本刀不启用运行时强制（会话所有权前提未满足，见 §Assumptions）。

---

### Edge Cases

- **默认档零破坏（最高优先）**：`oryxos.web.rbac.enabled` 缺省 `false` 时，容器注入 `AuthorizationService.ALLOW_ALL`（`AuthorizationConfig` 的唯一裁决源），强制点 `RbacEnforcer` 恒放行；`ALLOW_ALL` 恒返回 `Decision.ALLOWED`——「关 = 与无授权层逐字节一致」。
- **开关误配**：`rbac.enabled=true` 但认证门（`web.auth.enabled` / `web.apikey.enabled`）都关时，没有任何门会产出主体；此时**不得**让匿名主体把默认部署打成全网 403（单机零配置会被自己的授权层锁死）。处理：上游 Filter 在自身 `isEnabled()=false` 时最早返回、根本不调用强制点；启动期对该组合给出明确告警/拒绝（FR-012）。
- **`/api/v1/auth/*` 与 `POST /api/v1/auth/login`**：登录必须在 RBAC 开启时依然可用（否则无人能取得主体）。该子树沿用 018 的豁免定位，不参与裁决。
- **空角色主体**：账号被删/被禁用、`web_users.roles` 被清空、未知角色串（如手改成 `ADMINX`）——解析结果为空即**不授权**（拒绝），未知 token 记 WARN 并按「忽略该 token」降权处理，绝不向上兜底成 ADMIN。
- **`sessions.user_id` 不得作为主体源**：控制台把它写成常量 `default`（`SessionApiController.java:52`、`:76`），任何从会话推导主体的实现都会把所有控制台用户等同于同一个人——本刀明令禁止（FR-001）。
- **同请求同时携带管理台 session 与 API Key**：018 的「任一有效即放行」不变；但**角色不取并集**——主体自身已解析出的角色优先，避免低权限账号借同请求里的高权限 Key 提权。
- **拒绝的可区分性**：403 响应不得区分「资源不存在」与「存在但无权」（防枚举）；`401`（认证）与 `403`（授权）不混用；错误信息只进服务端日志与审计，不回显内部判定细节。
- **`/actuator/*`**：在 018 的门禁模式内，因此同样接受裁决；`/actuator/health` 子树维持 018 的豁免不变。
- **存量库升级**：V8 在既有 SQLite 库上以 PRAGMA 探测补列（`BaseSqliteMigration` 先例），在 PG 上执行同版本 SQL；两 vendor 同号、只增不改、不依赖 `hibernate.ddl-auto`；迁移失败不得让服务带病启动。
- **会话有效期 × 角色撤销**：不缓存角色 → 下一次请求即按新角色裁决；这是 012「session 不查 enabled」与「撤权要即时」两条口径的折中，工作会话本身仍按 012 规则存活到过期。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 用唯一主体类型 `Principal{kind, id, displayName, roles}` 表达请求来源，`kind ∈ {USER, API_KEY, ANONYMOUS}`；主体 MUST NOT 为 `null`（匿名按固定标识 `anonymous` 归一）。主体 MUST 是请求级的不可变值（Web 侧用 servlet 请求属性承载，MUST NOT 用可泄漏到下一个请求的全局静态/未清理的 ThreadLocal）。系统 MUST NOT 从 `sessions.user_id` 推导主体。
- **FR-002**: 系统 MUST 只有一处授权决策实现（`AuthorizationService` 的唯一实现类）；API、管理台、运行时三条路径 MUST 共用它；Controller 与 Filter MUST NOT 直接比对角色做判定（角色只作主体属性，判定只经 `decide(...)`）。
- **FR-003**: 系统 MUST 以最小角色矩阵表达权限：`VIEWER ⊆ EDITOR ⊆ ADMIN` 逐级包含，矩阵 MUST 集中在一处、可人工通读；本刀 MUST NOT 新增第四档角色（扩档先改 spec）。
- **FR-004**: flag 开启时，授权 MUST 在两扇既有 Filter 内部收口；MUST NOT 新增 URL pattern、MUST NOT 修改 `ApiKeyFilterConfig.PROTECTED_URL_PATTERNS` 与 `AuthFilterConfig` 的 `/admin/*` 注册、MUST NOT 引入 Spring Security filter chain（`spring-security-crypto` 单 jar 的既有边界不变）。
- **FR-005**: 系统 MUST 提供独立开关 `oryxos.web.rbac.enabled`，默认 `false`（宪法级约束，对齐 018 SC-001「回归零破坏」）。关闭时：零新增必填配置、既有测试零改动全绿、任一端点行为与引入授权层之前一致。
- **FR-006**: 拒绝 MUST 返回 HTTP 403 与项目统一响应信封（`ApiResponse`）及可读理由；401 MUST 继续由认证门产出且响应体不变；403 响应 MUST NOT 泄露「资源是否存在」的区分信息。
- **FR-007**: 每次拒绝 MUST 落一条 `authz_events` 记录（主体类别、主体标识、动作、资源描述、理由、请求方法与路径、时间）；放行 MUST NOT 落库；审计写入失败 MUST NOT 改变裁决（拒绝不得因审计失败变成放行），失败 MUST 有 ERROR 级日志。审计记录 MUST NOT 含凭证明文（API Key 只允许出现名称）。
- **FR-008**: 管理台账号主体的角色 MUST 每请求解析、MUST NOT 缓存；角色权威源是 `web_users.roles`（V8 新增列）。在角色落库落地前，主体角色来自显式配置的默认档（`oryxos.web.rbac.roles.default-user-roles`），该键 MUST 为**过渡入口**并在启动日志中体现。解析不到角色（账号不存在、已禁用、角色列为空或全部为未知 token）时 MUST 不授权（拒绝），未知角色 token MUST 记 WARN 后忽略、MUST NOT 兜底成高权限。
- **FR-009**: API Key 主体 MUST 以 Key 名称标识（绝不含明文），角色默认**空**（不授权），需要放行时由部署方显式配置（`oryxos.web.rbac.roles.default-api-key-roles`）；Key 主体 MUST 有治理上限——`MANAGE_MEMBERS` 与 `MANAGE_POLICIES` 永不放行。
- **FR-010**: 路径到动作的映射 MUST 按 [contracts/authorization-contract.md](contracts/authorization-contract.md) §4 的固定表执行；未登记路径 MUST 拒绝并留痕（fail-closed）；系统 MUST 在启动期枚举 `/api/v1/**`、`/api/v2/**` 的全部端点并校验「已映射或已登记豁免」，出现遗漏 MUST 拒绝启动并打印路径清单。
- **FR-011**: 角色赋值 MUST 通过 CLI 提供：`oryxos user role <username> <VIEWER|EDITOR|ADMIN>`，并在 `oryxos user list` 输出中显示角色列；本刀 MUST NOT 放开管理台角色管理页面（对齐 018「管理面 CLI-only」先例）。
- **FR-012**: 启动校验 MUST 覆盖：① `rbac.enabled=true` 但 `web.apikey.enabled=false`（无主体载体，授权会静默失效）→ 明确报错并拒绝启动，指路修正；② `rbac.enabled=true` 且库中无任何 ADMIN 账号（治理面锁死）→ 拒绝启动，指路 `oryxos user role <name> ADMIN`；③ 发现未知角色 token → WARN 并按 FR-008 降权；④ `rbac.enabled=true` 且 `web.auth.enabled=false` → WARN（管理台数据面不可用，沿用 018 口径）。任一情况 MUST NOT 静默。
- **FR-013**: 表结构变更 MUST 走 Flyway `db/migration/{sqlite,postgresql}/` 双 vendor 同版本号各一份（V8）：SQLite 侧为 `JavaMigration` + `BaseSqliteMigration`（无 `ADD COLUMN IF NOT EXISTS`，用 PRAGMA 探测补列，V6 先例），PostgreSQL 侧为同版本 SQL；MUST NOT 依赖 `hibernate.ddl-auto`，MUST NOT 修改任何已应用的迁移脚本。
- **FR-014**: 既有契约 MUST 零变化：018 的豁免清单（`OPTIONS`、`/api/v1/health`、`/actuator/health*`、`/api/v1/auth/**`）逐条不变；012 的 `/admin/**` 认证语义与 `web_sessions`/`web_users` 既有列不变；020 的工具策略与沙箱正交关系不变；`tool_invocations` / `llm_calls` 的写入口径与列不变。

### Key Entities

- **主体（Principal）**：一次请求「是谁、以什么身份」。属性：类别（用户/API Key/匿名）、标识（用户名 / Key 名称 / 固定占位）、展示名（仅供审计与界面）、角色集合（不可变）。它是三条路径共享的最小事实。
- **角色（Role）**：主体档位，三档 `VIEWER`/`EDITOR`/`ADMIN`，逐级包含。回答「这个人是什么档」，不回答「他能碰哪个具体资源」。
- **动作（Action）**：受控词表，11 个取值，读/运行/管理三族；授权矩阵的横轴。用枚举而非自由字符串，避免出现「漏判的写法默认放行」。
- **资源引用（ResourceRef）**：动作作用对象的「类型 + 标识」最小描述，不可变、不含业务数据，可安全进入审计；本刀裁决只到动作粒度，资源标识只用于审计与后续资产级扩展（#463）。
- **裁决结果（Decision）**：`{allowed, reason}`；拒绝理由必须是能被人读懂的一句话（进审计与管理台），不是错误码。
- **授权事件（authz_events）**：授权拒绝的落库记录。本刀只写拒绝行；放行不落库。
- **账号角色（web_users.roles）**：管理台账号的角色权威源（目标形态），逗号分隔的规范化角色名集合，默认 `VIEWER`（存量账号升级后即为只读档）；落库落地前由 `oryxos.web.rbac.roles.default-user-roles` 过渡，见 §Clarifications「待补」条。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 默认档（`rbac.enabled` 缺省 `false`）下，全仓既有测试零改动 100% 通过，任一端点行为无差异；零新增必填配置。
- **SC-002**: flag 开启后，路径 × 三档角色的裁决表 100% 有测试覆盖（用例数落卷）；越权动作执行次数 = 0；矩阵内动作 100% 放行。
- **SC-003**: 拒绝 100% 可在 `authz_events` 中按「主体 / 动作 / 时间」筛出，字段齐全率 100%；放行记录的落库条数 = 0。
- **SC-004**: 403 与 404/401 语义不混用；对同一动作的「无权」与「资源不存在」响应不可区分（防枚举演练 100% 通过）。
- **SC-005**: `/api/v1/**`、`/api/v2/**` 端点覆盖率 100%：全部落到「已映射动作」或「登记豁免」；故意新增一个未登记端点时，启动即拒绝（用例钉死）。
- **SC-006**: 单次 `decide(...)` 为内存纯函数、耗时可忽略（目标 ≤0.1ms，阈值写进用例）；每个请求的主体解析 ≤1 次索引查询；授权对请求 p99 的占比 <1%。
- **SC-007**: 文档同步与本特性行为一致：`CLAUDE.md`（配置段与模块口径）、`config/application.yml.example`（新配置键与注释）、`docs/CliGuide.md`（`oryxos user role`）、`website/docs/` 与 `website/zh/docs/`（认证/RBAC 说明成对更新）。

## Assumptions

- **授权范围的权威源**：本刀的角色权威源目标是 `web_users.roles`（V8 新增列，见「角色落库是过渡与目标的交界」条）；API Key 无库内归属，走显式配置的默认档。**边界（租户/组织/项目）的正式模型不在本刀**，`Action`/`ResourceRef` 里的 `WORKSPACE` 仅表示「本部署的授权范围整体」。
- **与既有体系的关系（逐条正交）**：012 管「管理台进门」、018 管「REST 机器调用进门」、020 管「Agent 能不能用某个工具」、沙箱管「工具能碰什么资源」；本刀只管「谁能不能做这件事」。四者独立配置、叠加生效，互不豁免，也不互相替代。
- **收口位置**：授权在两扇既有 Filter 内、认证成功之后调用；运行时不强制（`AgentService.process` 不新增主体参数）。运行时复用同一接口的结构已就位（接口与值对象在 `oryxos-core`，运行时无需依赖 `oryxos-web`），启用运行时授权需要会话所有权先落地，见下条。
- **会话所有权（不做）**：`sessions.user_id` 被控制台写成 `default`，且 `POST /api/v1/sessions/{id}/messages`、`GET/DELETE /api/v1/sessions/{id}` 今天无任何归属检查（`SessionApiController.java:85/107/126/138`）。任何「按会话归属授权」的设计都会在控制台写出真实 `user_id` 之前形同虚设甚至放大越权，故本刀明确不做；它的前提是控制台先落真实用户标识。
- **API Key 主体默认档是配置项而非硬编码**：默认空（机器凭证不默认授权），需要放行时由部署方显式配置（如 `[EDITOR]`），并始终叠加实现层已固化的能力上限（不放行成员与策略）。这是一条**待 maintainer 裁决**的取舍（见 §Clarifications 第 3 条）。
- **角色落库是过渡与目标的交界**：本阶段工作树的实现以配置默认档提供角色（`oryxos.web.rbac.roles.default-user-roles` 默认 `ADMIN`，防单机部署自锁）；本 spec 的目标形态是 `web_users.roles` 落库 + CLI 赋值（见 §Clarifications「待补」条与 data-model.md），二者在同一刀内先落后者、再把默认档收紧为空。实现进度以 [acceptance-report.md](acceptance-report.md) 的差异清单为准。
- **管理台按钮级隐藏不做**：本刀只保证 403 是统一信封 + 人话理由，页面据此可提示；按角色隐藏/置灰按钮属体验增强，留后续（避免在没有前端组件测试基座的情况下承诺 UI 行为）。
- **明确不做（逐条给去向）**：OIDC/SSO 登录与企业身份映射（#461）；Agent/Skill/知识库/Connector 的 owner、版本、可见范围（#463）；三级租户模型（组织/部门/项目）与资源级 ACL（#462 后续刀）；会话所有权强制；per-key scope 与按 Key 限流（v1.0 租户模型）；认证事件审计（登录成功/失败/登出/Key 使用，#461）；Spring Security 全套（filter chain / autoconfig / `@PreAuthorize`，维持 012 已记录边界）；管理台角色管理页面（本刀 CLI-only）。
- **依赖**：复用 012 的 `WebUser`/`WebUserService`/`WebSessionService`、018 的 `ApiKeyService`/两扇 Filter 与统一信封 `ApiResponse`、020 的「策略只做减法、拒绝留痕可筛」口径、025 的 Flyway 双 vendor 迁移体系与 `BaseSqliteMigration`。
