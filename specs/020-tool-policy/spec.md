# Feature Specification: Tool Policy 工具策略

**Feature Branch**: `020-tool-policy`

**Created**: 2026-08-27

**Status**: Draft

**Input**: User description: "Tool Policy 工具策略（020-tool-policy）：全局 + Agent 级工具 allow/deny，v0.3 治理面第一刀——治理走「事先定义、配置即责任」（HITL 审批已裁决移出主线，见 roadmap 备忘）。要点：现状 AGENT.md frontmatter 的 tools: 列表是 Agent 作者的自选集，缺的是平台管理员的治理层——新增全局策略（管理员维护）：全局 deny 清单最高优先（任何 Agent 不可用被 deny 的工具，无论 frontmatter 怎么写），可选按 Agent 的管理员侧覆写（给指定 Agent 收紧或放宽）；有效工具集 = Agent 自选 ∩ 管理员允许 − 全局 deny；策略覆盖内置工具与 MCP 工具（按注册名，MCP 支持 server 级通配如 github-mcp:*）；执行面三道保险：被 deny 的工具不注入 prompt（事前不可见）、ToolExecutor 拒绝执行（事中兜底，防模型幻觉调用）、tool_invocations 审计标记策略拒绝（事后留痕）；管理台策略页可查看全局策略与每个 Agent 的有效工具集、可编辑热更新；策略变更即配置变更（可审计可追溯，责任明确）；默认无全局策略 = 现状零破坏；参数级细粒度策略与 RBAC 不做（留后续）。"

## Clarifications

### Session 2026-08-28

- Q: 工具策略存在哪，追溯靠什么？ → A: SQLite，镜像沙箱白名单模式——与 `sandbox_whitelist`/providers 等既有「管理台可编辑的治理配置」同模式：管理台可编辑、热更新、记录最近更新时间与来源（FR-013 最低追溯口径）。
- Q: 全局 deny 的按 Agent 例外（exempt）本期做吗？ → A: 保留——例外是事先显式登记的配置（非临时授权），有效集仍 ⊆ 声明集；没有它全局 deny 在真实环境几乎不可用（禁 shell 会连运维 Agent 一起禁死）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 全局 deny：管理员一条配置管住所有 Agent (Priority: P1)

平台管理员判定 `shell` 在本环境风险过高，在全局策略里将它列入 deny 清单。此后所有 Agent——无论其 `AGENT.md` 里怎么声明——都用不了 shell：模型在对话中根本看不到这个工具（事前不可见）；即便模型幻觉式地发起调用，执行层也会拒绝并留下「策略拒绝」的审计记录（事中兜底、事后留痕）。Agent 的其余工具照常可用，任务继续。未配置任何全局策略的部署，一切行为与现状完全一致。

**Why this priority**: 这是治理层的本体——「Agent 作者声明想用什么」与「平台管理员允许用什么」的分离。全局 deny 是最小、最常用、责任最清晰的治理动作（一条配置管全场），独立交付即可回应「96% 上生产、12% 管得住」的核心焦虑。

**Independent Test**: 配置全局 deny 含 `shell` → 用声明了 shell 的 Agent 对话：① 询问其可用能力，回复不含 shell；② 引导其执行命令，工具不被执行、审计出现策略拒绝记录、Agent 能解释无法执行；③ 该 Agent 的其它工具正常。清空 deny 后行为恢复；从未配置策略时全量既有行为不变。

**Acceptance Scenarios**:

1. **Given** 全局 deny 含 `shell`，**When** 声明了 shell 的 Agent 处理消息，**Then** 提供给模型的工具清单不含 shell（事前不可见），其余声明工具正常在列。
2. **Given** 同上，**When** 模型仍发起 shell 调用（幻觉/注入诱导），**Then** 执行层拒绝、目标命令零执行，以「被平台策略禁止」的失败结果回填模型，模型可向用户解释；`tool_invocations` 留下策略拒绝标记的失败记录。
3. **Given** 全局 deny 含某 MCP server 通配（如 `github-mcp:*`），**When** 任何 Agent 使用该 server 下的任意工具，**Then** 全部按场景 1/2 的口径被拒。
4. **Given** 未配置任何全局策略（默认），**When** 任何 Agent 执行任何工具，**Then** 行为与本 feature 之前完全一致（回归零破坏）。
5. **Given** deny 导致某 Agent 的声明工具全部不可用，**When** 该 Agent 处理消息，**Then** 正常以纯对话方式运行（无工具可用不是错误），启动/加载时有清晰告警提示管理员。

---

### User Story 2 - 按 Agent 覆写：例外放宽与定向收紧 (Priority: P2)

全局 deny 了 `shell`，但运维 Agent（ops-agent）的本职就是执行命令——管理员在策略里为 ops-agent 显式登记例外（exempt），只有它可以继续用 shell；其它 Agent 仍被禁。反过来，某个面向外部群聊的客服 Agent 虽然声明了 `http_post`，管理员认为它不该有外发能力——为它单独登记收紧（agent 级 deny），只影响这一个 Agent。每条例外/收紧都是显式配置，谁改的、什么时候改的可追溯。

**Why this priority**: 全局一刀切在真实环境必然产生例外需求；显式登记的例外保持了「事先定义、配置即责任」——比运行时人工审批（已裁决移出）责任链清晰得多。依赖 US1 的策略框架，故为 P2。

**Independent Test**: 全局 deny `shell` + ops-agent 例外 → ops-agent 能用 shell、其它 Agent 不能；为客服 Agent 配 agent 级 deny `http_post` → 仅它失去该工具、其它 Agent 不受影响；例外/收紧的每次变更在配置中可追溯。

**Acceptance Scenarios**:

1. **Given** 全局 deny 含 `shell` 且 ops-agent 登记为例外，**When** ops-agent 处理消息，**Then** shell 对它可见且可执行；其它声明了 shell 的 Agent 仍按 US1 被拒。
2. **Given** 为某 Agent 配置 agent 级 deny 含 `http_post`，**When** 该 Agent 处理消息，**Then** http_post 对它不可见且调用被拒；其它 Agent 的 http_post 不受影响。
3. **Given** 同一工具同时命中「全局 deny + 该 Agent 例外 + 该 Agent 级 deny」，**When** 求该 Agent 的有效工具集，**Then** 按固定优先级收敛（agent 级 deny 最终收紧，例外仅解除全局 deny），结果确定且可在管理台看到裁决依据。
4. **Given** 例外登记指向不存在的 Agent 或未知工具名，**When** 策略加载，**Then** 给出清晰告警（不静默、不阻断其余策略生效）。

---

### User Story 3 - 策略可视：管理台看得见、改得动 (Priority: P3)

管理员打开管理台策略页：看到全局 deny 清单、各 Agent 的例外与收紧登记，以及每个 Agent 最终的「有效工具集」——声明了什么、被策略去掉了什么、为什么（命中哪条规则）。页面上可直接编辑策略并热更新（改完即对后续调用生效，无需重启）；策略拒绝的调用在既有审计页可按标记筛出。

**Why this priority**: 「配置即责任」要成立，前提是配置结果可见——管理员必须能回答"这个 Agent 现在到底能用什么、为什么"。纯体验与可视化增强，不影响 US1/US2 的正确性成立。

**Independent Test**: 策略页展示全局/各 Agent 策略与有效工具集（含裁决依据）；页面编辑 deny 清单后不重启即对下一次调用生效；审计页能筛出策略拒绝的调用记录。

**Acceptance Scenarios**:

1. **Given** 已配置若干策略，**When** 打开管理台策略页，**Then** 可见全局 deny 清单、各 Agent 例外/收紧、每个 Agent 的有效工具集及每项被移除工具的命中规则。
2. **Given** 管理员在策略页把 `write_file` 加入全局 deny，**When** 保存，**Then** 无需重启，下一次任何 Agent 的消息处理即按新策略生效（事前不可见 + 事中拒绝）。
3. **Given** 发生过策略拒绝的调用，**When** 在审计页按策略拒绝筛选，**Then** 能列出对应记录（Agent、工具、时间）。

---

### Edge Cases

- **策略与沙箱正交**：Tool Policy 管「这个 Agent 能不能用这个工具」，沙箱白名单管「工具执行时能碰什么资源」——两道都过才执行；策略放行不豁免沙箱，删除任一方不影响另一方语义。
- **热更新与进行中的 ReAct**：本轮 prompt 已注入旧工具集时策略变更——事中保险按执行瞬间的最新策略裁决（可见但已被禁 → 执行被拒留痕）；下一轮 prompt 即用新集。
- **MCP server 卸载后策略残留**：指向已不存在工具/server 的策略条目无害，加载时告警提示清理。
- **策略配置非法**（未知字段、语法错误）：启动/加载时清晰报错，不静默失败（既有配置校验口径）。
- **通配与精确名冲突**：`github-mcp:*` deny 但例外登记了 `github-mcp:list_issues`——例外按同样的名称/通配语义匹配，精确名优先于通配。
- **定时任务/飞书渠道触发**：策略对所有触发源一视同仁（策略在工具面生效，与触发渠道无关）。
- **save_memory/recall_memory 等记忆类工具被 deny**：Agent 失去记忆能力但照常对话——策略不区分工具"重要性"，后果由配置者负责（告警提示）。
- **审计标记与既有失败的区分**：策略拒绝在审计中可与普通执行失败、沙箱拒绝区分开（独立可筛）。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 支持管理员维护的全局工具策略：全局 deny 清单（工具名精确匹配 + MCP server 级通配），对所有 Agent 生效且优先于 `AGENT.md` 的 `tools:` 声明；默认清单为空 = 一切行为与现状一致（回归零破坏）。
- **FR-002**: 系统 MUST 支持按 Agent 的管理员侧覆写：例外登记（exempt，解除全局 deny 对指定 Agent 的作用）与 agent 级 deny（对指定 Agent 额外收紧）；覆写只能由平台策略配置表达，MUST NOT 通过 `AGENT.md` 表达（治理层与 Agent 作者层分离）。
- **FR-003**: Agent 的有效工具集 MUST 按固定规则收敛：声明集（`tools:` + MCP 绑定）− (全局 deny − 该 Agent 例外) − 该 Agent 级 deny；同一输入 MUST 产生确定结果；精确名匹配优先于通配。
- **FR-004**: 事前保险：被策略移除的工具 MUST NOT 出现在提供给模型的工具清单中（prompt 不可见）；每次消息处理 MUST 按当时的策略计算有效集（策略热更新后下一轮生效）。
- **FR-005**: 事中保险：执行层 MUST 在每次工具执行前按执行瞬间的最新策略校验，被禁工具的调用 MUST 拒绝且目标动作零执行，以「被平台策略禁止」的可读失败结果回填模型（模型可向用户解释）。
- **FR-006**: 事后保险：策略拒绝的调用 MUST 写入 `tool_invocations` 审计并携带可区分的策略拒绝标记（与普通失败、沙箱拒绝可分别筛选）。
- **FR-007**: 策略 MUST 覆盖内置工具与 MCP 工具（按注册名）；MCP 支持 server 级通配（如 `github-mcp:*`）与单工具精确名两种粒度。
- **FR-008**: 策略配置 MUST 可热更新（变更后无需重启即对后续处理生效）；策略加载 MUST 校验：未知工具名/不存在的 Agent/非法语法给出清晰告警或报错，不静默失败。
- **FR-009**: 管理台 MUST 提供策略页：展示全局 deny、各 Agent 例外/收紧、每个 Agent 的有效工具集与每项被移除工具的命中规则；支持编辑与保存（热更新）。
- **FR-010**: REST MUST 提供策略的查询与更新端点（管理台页面的数据面；受 018 认证门禁保护）。
- **FR-011**: 策略导致 Agent 声明工具全部不可用时，Agent MUST 照常以纯对话运行；策略加载 MUST 对「全空工具集」「策略指向不存在目标」输出告警。
- **FR-012**: 策略与沙箱 MUST 保持正交：策略放行不豁免沙箱白名单校验，沙箱配置不影响策略裁决；两者独立可配、叠加生效。
- **FR-013**: 策略变更 MUST 可追溯：持久化存储中保留最近更新时间与更新来源（管理台/REST 调用方标识），满足「配置即责任」的最低追责口径（完整变更历史与 RBAC 留后续）。

### Key Entities

- **工具策略（ToolPolicy）**: 平台级治理配置。组成：全局 deny 清单（工具名/通配）、按 Agent 的例外登记（agent → 工具名/通配列表）、按 Agent 的收紧登记（agent → 工具名/通配列表）、最近更新时间与更新者标识。独立于 `AGENT.md`（Agent 作者层）存在。
- **有效工具集（EffectiveToolSet）**: 派生视图（不落库）：某 Agent 在当前策略下实际可用的工具清单，附每项被移除工具的命中规则说明。管理台策略页与工具清单注入均以此为准。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 未配置任何策略时，全量既有测试与行为 100% 不变（回归零破坏）。
- **SC-002**: 被 deny 工具的目标动作执行次数为 0：事前 100% 不出现在模型工具清单，事中幻觉调用 100% 被拒。
- **SC-003**: 有效工具集收敛确定性 100%：同一策略输入反复求值结果一致；US2 场景 3 的三重叠加按固定优先级收敛。
- **SC-004**: 策略热更新后，下一次消息处理即按新策略生效（无需重启），生效延迟不超过一次消息往返。
- **SC-005**: 策略拒绝的调用 100% 可在审计中按标记筛出，且与沙箱拒绝、普通失败可区分。
- **SC-006**: 管理员在策略页能对任一 Agent 回答"它现在能用什么、少了什么、为什么"（有效工具集 + 命中规则 100% 可见）。
- **SC-007**: 走查 Demo（roadmap v0.3 口径）可完整演示：配置 deny → 模型试图调用越权工具被拒且留痕 → 审计按标记查到记录。

## Assumptions

- **策略存储在平台侧数据库**（Clarifications 裁决：SQLite，镜像沙箱白名单等既有治理配置模式），与 `.oryxos/agents/` 目录分离——Agent 作者改不了治理层；`AGENT.md` 的 `tools:` 语义不变（仍是作者自选集）。
- **deny 优先的三层收敛**：例外（exempt）只解除全局 deny，不能授予 Agent 未声明的工具（有效集永远 ⊆ 声明集——策略只做减法与减法的豁免，不做加法）。
- **匹配语义**：工具名精确匹配；MCP 通配仅支持 `server:*` 形态（不做任意 glob）；精确名优先于通配。
- **变更追溯取最低口径**：记录最近更新时间与更新来源；完整变更历史（逐版 diff）与审批流留后续（可配合 git 管理配置文件获得历史）。
- **不做的**：参数级/条件化策略（如"shell 只许跑某些命令"——那是沙箱 `allowed_commands` 的职责）、RBAC/多租户（v1.0）、按渠道/时段的策略维度、`AGENT.md` 内表达治理策略。
- **依赖**：018 认证门禁（策略端点保护）、既有 `tool_invocations` 审计体系（新增策略拒绝标记，表结构变更遵循手工 schema 口径）、管理台既有页面骨架。
