# Implementation Plan: 企业身份与授权基座（Identity & Authorization）

**Branch**: `039-identity-authorization` | **Date**: 2026-09-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/039-identity-authorization/spec.md`

## Summary

在既有两扇自研 Filter 之上补「谁能不能做这件事」：统一主体 `Principal`（core/auth，请求属性承载，绝不用 `sessions.user_id` 推导）、唯一决策点 `AuthorizationService`（core/policy，与 `ToolPolicyService` 同包不撞名，裁决结果统一为 `Decision`）、最小角色矩阵 `VIEWER ⊆ EDITOR ⊆ ADMIN` + API Key 能力上限、拒绝落 `authz_events`（V8 双轨）。技术路线：三个新值对象进 `oryxos-core`（零依赖、纯函数、运行时可复用，不需依赖 `oryxos-web`）；授权调用收口在两扇**既有** Filter 内部（`PrincipalHolder` 请求属性 → `RbacEnforcer` → `AuthorizationService`），**不新增 URL pattern、不引 Spring Security**；角色权威源落 `web_users.roles`（V8：SQLite 走 `JavaMigration` + `BaseSqliteMigration` 补列，PG 走同号 SQL）；角色赋值走 CLI（`oryxos user role`），管理台不放开页面。零破坏锚点：`oryxos.web.rbac.enabled` 默认 `false`，关闭时注入 `AuthorizationService.ALLOW_ALL`，行为与引入授权层之前逐字节一致（对齐 018 SC-001）。零新增依赖、零新模块。实施分两阶段：**阶段一**（工作树已落地）统一主体、决策契约与矩阵、请求级承载、强制点、开关与配置默认档；**阶段二**（tasks.md T001~T046）角色落库与 CLI 赋值、路径映射升级、拒绝审计落库、启动校验与端点全覆盖、E2E 与文档同步——两阶段的实现进度以 [acceptance-report.md](acceptance-report.md) 的差异清单为准。

## Technical Context

**Language/Version**: Java 21（虚拟线程，同现状）

**Primary Dependencies**: Spring Boot 3.x（Spring MVC servlet filter）、Spring Data JPA、Picocli；零新增依赖（枚举矩阵与纯函数裁决不引任何库）

**Storage**: Flyway `db/migration/{sqlite,postgresql}/` 双 vendor 同版本号 V8——`web_users` 加 `roles` 列 + 新建 `authz_events` 表；SQLite 侧 `JavaMigration` + `BaseSqliteMigration`（无 `ADD COLUMN IF NOT EXISTS`，PRAGMA 探测，V6 先例），PG 侧 `V8__web_user_roles.sql`；不依赖 `hibernate.ddl-auto`

**Testing**: JUnit 5——core（`Principal` 归一/不可变、矩阵与 Key 上限、`Decision` 语义）、storage（角色列读写与未知 token 降权、`authz_events` 落库）、web（两扇 Filter 的裁决路径表、`RbacEnforcer` 403 与审计、启动校验四组合、`/auth/me` 角色）、boot E2E（真实 HTTP+SQLite 的三档角色放行/拒绝与默认档零回归）；`mvn verify` 全量门禁

**Target Platform**: Linux server（单 fat JAR，同现状）

**Project Type**: Maven 多模块单体——涉及 oryxos-core（主体/动作/决策契约）、oryxos-storage（角色列 + 审计落库 + V8 迁移）、oryxos-web（两扇 Filter、强制点、启动校验、`/auth/me`）、oryxos-cli（`oryxos user role` 子命令）、oryxos-boot（E2E）

**Performance Goals**: 单次 `decide(...)` 内存纯函数 ≤0.1ms（SC-006）；每请求主体解析 ≤1 次索引查询；授权对请求 p99 占比 <1%

**Constraints**: 默认关零破坏（SC-001，宪法级）；授权只收口在既有 Filter 内部、零 URL pattern 变更（FR-004）；同步阻塞模型（宪法 VII，无 Reactor/CompletableFuture）；拒绝审计失败不得改变裁决（FR-007）

**Scale/Scope**: 角色三档、动作 11 个、路径映射约 20 行表；约 14 个新文件（含测试）+ 8 个既有文件小改

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 评估 | 结论 |
|------|------|------|
| I 自实现 ReAct 循环 | 不涉及：本刀不改 `ReActLoop` / `ToolExecutor` 执行路径，运行时不强制授权（运行时复用同一接口的结构已就位） | ✅ |
| II Spring AI 边界 | 不涉及 | ✅ |
| III Provider 显式映射 | 不涉及 | ✅ |
| IV 目录=Agent / Skill 软连接 | 不涉及：不动 `.oryxos/agents|skills` 目录语义与绑定真相源；`MANAGE_AGENTS`/`MANAGE_SKILLS` 只是动作词表新增，不改变文件面 | ✅ |
| V 审计 Day One 落库 | 强化：授权拒绝新增 `authz_events` 落库（只写拒绝、放行不落库，零写放大）；`tool_invocations`/`llm_calls` 写入口径与列零变化 | ✅ |
| VI 安全是地基 / 不用 SecurityManager | 本刀即安全面：默认拒绝、最小权限、Key 主体治理上限（不得改策略与成员）、未知 token 降权不兜底、恒不缓存角色；无新凭证面、审计不含明文（Key 只出现名称） | ✅ |
| VII 同步执行 + 虚拟线程 | `OncePerRequestFilter` 内同步裁决，内存纯函数；无 Reactor/CompletableFuture/异步审计队列 | ✅ |
| VIII 状态外置 / Flyway 迁移 | 角色与授权事件落库（不落进程内存）；V8 双 vendor 同号、SQLite 走 `BaseSqliteMigration` PRAGMA 探测、只增不改、不依赖 `hibernate.ddl-auto` | ✅ |
| 模块约束 | 跨模块契约（`Principal`/`Role`/`Action`/`ResourceRef`/`AuthorizationService`）进 `oryxos-core`；持久化面（角色列、`authz_events`、迁移）落 `oryxos-storage`；Web 侧只做接线与拒绝语义；零新模块、无循环依赖；`CLAUDE.md` 配置段与行为口径同步 | ✅ |

**Phase 1 设计后复评**: 通过。两处需显式声明的偏差已写入 research R4/R10（决策实现落 core 而非 storage 的理由；术语「边界」暂不引入租户名词）。无需 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/039-identity-authorization/
├── spec.md              # 需求：US1~US3 / FR-001~FR-014 / SC-001~SC-007 / Clarifications
├── plan.md              # 本文件
├── research.md          # Phase 0：R1~R11 技术裁决
├── data-model.md        # Phase 1：web_users.roles 列 + authz_events 表 + V8 双轨
├── quickstart.md        # Phase 1：V1~V6 验收走查
├── contracts/
│   └── authorization-contract.md  # Phase 1：主体/矩阵/路径裁决/审计/兼容承诺
├── tasks.md             # Phase 2 任务分解（T001~T046，6 阶段）
├── checklists/
│   └── requirements.md  # spec 质量清单（16 项 + 中文 Notes）
└── acceptance-report.md # 验收落卷（本阶段：待验收 stub）
```

### Source Code (repository root)

```text
oryxos-core/
├── src/main/java/io/oryxos/core/auth/
│   ├── Principal.java                    # 已落地：统一主体（Kind/工厂/isAuthorizable/describe）
│   └── Role.java                          # 已落地：VIEWER/EDITOR/ADMIN
├── src/main/java/io/oryxos/core/policy/
│   ├── Action.java                        # 已落地：11 个受控动作
│   ├── ResourceRef.java                   # 已落地：type+id 最小资源引用（含 TYPE_* 常量与工厂）
│   ├── AuthorizationService.java          # 已落地：唯一决策契约 + ALLOW_ALL + 嵌套 Decision
│   └── RoleBasedAuthorizationService.java # 已落地：最小角色矩阵实现（含 API Key 能力上限）
├── src/test/java/io/oryxos/core/auth|policy/
│   ├── PrincipalTest.java                 # 已落地：null 归一/角色冻结/isAuthorizable
│   └── RoleBasedAuthorizationServiceTest.java  # 已落地：三档矩阵、Key 上限、确定性、默认档

oryxos-storage/
├── src/main/resources/db/migration/postgresql/V8__web_user_roles.sql   # 新增：ALTER web_users 加 roles + CREATE authz_events
├── src/main/java/io/oryxos/storage/migration/
│   ├── WebUserRolesMigration.java         # 新增：SQLite V8（PRAGMA 探测补列 + 建表）
│   └── SqliteMigrationsConfiguration.java # 修改：注册 V8 Bean
├── src/main/java/io/oryxos/storage/
│   ├── WebUser.java                       # 修改：加 roles 映射（逗号分隔规范化集合）
│   ├── WebUserService.java                # 修改：rolesOf(username) 每请求解析 / setRoles(username, roles)（未知 token WARN 降权）
│   ├── AuthzEvent.java                    # 新增：授权事件实体（只写拒绝行）
│   ├── AuthzEventRepository.java           # 新增：按主体/动作/时间查询
│   └── AuthzEventRecorder.java             # 新增：落库封装（失败 ERROR 日志、绝不抛出改变裁决）
└── src/test/java/io/oryxos/storage/
    ├── WebUserRoleTest.java               # 新增：角色读写、未知 token、空角色
    └── AuthzEventRecorderTest.java        # 新增：落库字段齐全、失败不改裁决

oryxos-web/
├── src/main/java/io/oryxos/web/config/
│   ├── WebRbacProperties.java             # 已落地：oryxos.web.rbac.*（enabled 默认 false / deny-anonymous 默认 true）
│   ├── RoleMappingProperties.java         # 已落地：oryxos.web.rbac.roles.*（default-user-roles 默认 ADMIN 防自锁 / default-api-key-roles 默认空）
│   ├── AuthorizationConfig.java           # 已落地：按开关装配 AuthorizationService（关＝ALLOW_ALL）+ RbacEnforcer Bean
│   ├── WebAuthConfig.java                 # 不改：两属性由 AuthorizationConfig 自注册（本刀无需动既有配置类）
│   └── ApiKeyFilterConfig.java            # 已落地修改：注入 RbacEnforcer（模式数组一字不改）
├── src/main/java/io/oryxos/web/security/
│   ├── PrincipalHolder.java               # 已落地：请求属性承载（io.oryxos.web.principal）
│   ├── RbacEnforcer.java                  # 已落地：强制点（403 + 拒绝留痕）；待升级为路径映射裁决
│   ├── RequestActionResolver.java         # 新增：路径×方法 → (Action, ResourceRef)；未登记 = 拒绝
│   ├── RbacStartupCheck.java              # 新增：四组合校验 + /api/v1|v2 端点全覆盖校验
│   ├── BasicAuthFilter.java               # 修改：认证成功分支置主体（从 web_users.roles 解析）+ 调强制点
│   └── ApiKeyAuthFilter.java              # 已落地修改：两处成功分支置主体 + 调强制点（待补角色解析）
├── src/main/java/io/oryxos/web/controller/
│   ├── AuthApiController.java             # 修改：/me 返回角色集合与开关状态
│   └── dto/AuthMeView.java                # 修改：追加 roles / rbacEnabled（可空字段，向后兼容）
└── src/test/java/io/oryxos/web/security/
    ├── RbacEnforcerTest.java              # 新增：放行/403/匿名/审计调用/flag 关恒放行
    ├── RequestActionResolverTest.java     # 新增：路径表全覆盖 + 未登记 fail-closed
    └── RbacStartupCheckTest.java          # 新增：四组合 + 端点遗漏即拒

oryxos-cli/src/main/java/io/oryxos/cli/command/UserCommand.java   # 修改：新增 role 子命令 + list 显示角色列

oryxos-boot/src/test/java/io/oryxos/boot/
└── RbacAuthorizationE2ETest.java          # 新增：真实 HTTP + SQLite——三档放行/拒绝、默认档零回归、拒绝落库

config/application.yml.example             # 修改：oryxos.web.rbac.* 注释段
CLAUDE.md                                  # 修改：配置段与 RBAC 口径
docs/CliGuide.md                           # 修改：oryxos user role
website/docs/*.md 与 website/zh/docs/*.md   # 修改：认证与授权说明成对更新
```

**Structure Decision**: 契约与值对象进 `oryxos-core`（`Principal`/`Role` 在 `core/auth`，动作与决策在 `core/policy`），使运行时不必依赖 `oryxos-web` 即可复用同一决策——这是 #462「三条路径共用同一授权决策」的结构前提；持久化面（角色列、授权事件、V8 迁移）落 `oryxos-storage`，与 `ApiKeyService`/`WebUserService` 并列；Web 侧只做「接线 + 拒绝语义 + 启动校验」，强制点单独成类（`RbacEnforcer`）而不把裁决塞进两扇 Filter，避免「一扇门收紧、另一扇忘了收」。不新建模块；`/admin/*` 与 `PROTECTED_URL_PATTERNS` 的注册模式一字不改（FR-004）。

## 关键设计裁决（详见 research.md）

| # | 裁决 | 要点 |
|---|------|------|
| R1 | 主体载体与来源 | `Principal` 值对象 + 请求属性 `PrincipalHolder`（不用 ThreadLocal，防线程复用越权）；禁止从 `sessions.user_id` 推导（控制台写死 `default`） |
| R2 | 收口位置 | 授权在两扇既有 Filter 内、认证成功之后；零新增 URL pattern；强制点单独成类，唯一实现 |
| R3 | 角色矩阵表达 | `EnumSet` 逐级叠加（VIEWER ⊆ EDITOR ⊆ ADMIN）+ Key 能力上限（不减成员与策略）；角色不取并集，防跨凭证提权 |
| R4 | 实现落位 | 矩阵是零持久化依赖的纯函数，落 `core/policy`；持久化面落 `storage`——与 `ToolPolicyService` 范式的有意收窄，文档显式声明 |
| R5 | 默认关与开关关系 | `rbac.enabled` 默认 false；`ALLOW_ALL` 为默认注入；「认证开 → 授权才有对象」，上游 Filter 短路即不产生 403 |
| R6 | 路径 → 动作映射 | 固定表 + 未登记 fail-closed + 启动期端点全覆盖校验（把手维护遗漏从静默放行变成启动即拒） |
| R7 | 拒绝审计 | 新增 `authz_events`（只写拒绝、放行不落库）；写失败只记 ERROR，绝不把拒绝变放行；认证事件仍归 #461 |
| R8 | 403 语义 | 认证失败 401 原样、授权失败 403 统一信封 + 人话理由；无权与不存在不可区分（防枚举） |
| R9 | 角色来源与赋值面 | 目标：`web_users.roles` 落库（V8）+ CLI `oryxos user role`（管理台不放开页面）；过渡：`oryxos.web.rbac.roles.*` 显式默认档（user 默认 ADMIN 防自锁、api-key 默认空）；两者并存时以主体自带角色优先 |
| R10 | V8 迁移 | SQLite `JavaMigration` + `BaseSqliteMigration` 补列（V6 先例）+ PG 同号 SQL；只增不改、不依赖 ddl-auto |
| R11 | 兼容边界 | `/admin/**` 语义、018 豁免清单、020 工具策略与沙箱正交、两张审计表口径全部零变化；不引 Spring Security |
