# Feature Specification: Persona 人格 + 人格库（copy-in 模板库）+ Agent 导入 —— 整链

**Feature Branch**: `feat/025-persona-library`

**Created**: 2026-09-02

**Status**: Draft（实现已随本分支完整落地；Phase 6 全量回归 + 端到端验收走查后，在 [acceptance-report.md](./acceptance-report.md) 补证据）

**Input**: 三个能力段合为一条「内容资产」闭环引入 oryxos：

1. **Persona 七字段结构化人格**：`Profile.Persona`（name/role/traits/tone/values/boundaries/sample_style）作为 Agent 一等配置，`ContextLoader` 每轮固定注入 system prompt——有 persona 的 Agent 每轮人格段位置固定、格式固定、内容固定；无 persona 的老 Agent 零改变。
2. **agency-agents-zh 专家导入**：一份专家 `.md` → parser/importer 纯 POJO 映射 → `importAgent` 走与手工建 Agent 同一道 `saveFiles` 校验链 → 落盘 `.oryxos/agents/<slug>/AGENT.md` 即热加载可对话；CLI `agent import` + Web import-preview/import 两步走。
3. **人格库（copy-in 模板库）**：`oryxos-persona` 模块承载——内置 12 个专家人格 classpath 只读（升级自动携带、不播种进工作区），自定义人格 `.oryxos/personas/<key>.md` 可 CRUD、跨重启持久；从库导入 = **照搬源文件原文复制进 Agent（copy-in）**，不做按名引用；import-preview 对渲染出的完整 AGENT.md 跑真实 `AgentLoader.parse` dry-run（纯内存、不落盘），坏文件导入前就现形。

**整链价值**: 一句话——「一个人格从产生（persona 段 / 导入 / 人格库）到被 Agent 每轮执行（固定注入）形成闭环」。管理台把它做成可操作入口：Agent 详情「人格」卡（读/改）、「新建 Agent → 从人格库导入」（纯选择 → 预览 → 落盘）、独立「人格库」页（增删改查）。

**红线（不做，写死在这里）**:

- **人格市场（按名引用/共享人格实体）不做**：copy-in 与按名引用是两种抽象——前者只是把「源文件原文」变成工作区可增删改的载体，后者才是跨 Agent 共享实体。多 Agent 引用同一人格、改一处处处生效，等「大家都在写同一套人格」的信号再抽象。
- **oryxos 既有的「用大模型生成 Agent」分叉流不改造为 persona 版本**：`ensurePersona`（缺 persona 段用默认人格兜底的方法）随导入链一并迁入 core，但**不接线**进 `generateDraft`——生成流是 oryxos 既有分叉，与导入链正交，不在本特性内改它的产物形态。

---

## Part A — Persona 结构化人格与 Agent 导入

### User Story A1 - Agent 有人格、每轮恒定、老 Agent 零改变 (Priority: P1)

企业的每个 Agent 都有一份结构化「人设」：名字/角色/性格/语气/准则/边界/风格示范七要素。凡写了人设的 Agent，每一轮对话系统提示里都出现**位置固定、格式固定、内容固定**的人格段——会话怎么变、人格不变，模型先立住人设再干活。而没写人设的存量 Agent，行为与升级前完全一致。

**Why this priority**: 这是全节的机制地基。没有「人格恒定」，人设只是又一段会漂的 prompt；没有「缺省零改变」，老 Agent 一升级就变味。它决定了「结构化」比「再加一段 prompt」强在哪，也是后两条故事（导入、人格库、Web 三入口）的承载字段。

**Independent Test**: 给一个 Agent 写好人设，跑多轮对话确认每轮人格段都在「身份之后、任务正文之前」且内容逐字节相同；另一个 Agent 不写人设，确认对话输出不含任何人格段。可独立验证「恒定 + 兼容」这条闭环。

**Acceptance Scenarios**:

1. **Given** Agent 的配置里写了完整人设（含名字/角色/性格/语气/准则/边界/风格示范），**When** 连续发起多轮对话，**Then** 每轮系统提示中人格段位置固定（在身份描述之后、任务指令之前）、格式固定、字段渲染完整
2. **Given** Agent 的配置里没有任何人设段，**When** 发起对话，**Then** 系统提示不含人格段及其锚点，输出与升级前一致
3. **Given** 人设的「风格示范」字段给了 1~2 句示例回复，**When** 模型模仿时，**Then** 该字段作为语气锚点起效，而不是作为「必须逐条做到」的规则清单

### User Story A2 - 一键导入专家人格，同一道校验即上线 (Priority: P2)

管理员把一个现成专家人格文件（如「软件架构师」）导入 oryxos：一份文件 → 一个带人设、可立即对话的 Agent。导入器把源文件的「你是谁」（身份/记忆/沟通/风格/关键规则）映射成人设段、「你干什么」（使命/交付物/流程/指标）搬进任务正文；映射时做兜底（角色缺失用默认人设、本机没有的工具剔除、模型缺省用默认供应商）。导入产物与手工建 Agent 走**同一道校验**——坏文件报错、不写坏目录、失败回滚；重名拒绝不覆盖。

**Why this priority**: 这是内容来源，也是「人格库从第一天就有货」的关键——现成的专家人格一条命令变成 oryxos 里能跑、有人设的 Agent。它复用 specs/011 交付的 `saveFiles` 校验链，不新开校验路径，是「导入不破坏安全地基」的保证。

**Independent Test**: 导入一份合法专家文件 → 该 Agent 立即出现在列表并可直接对话、人设字段与源文件映射一致；再导同一份 → 报「已存在」拒绝；导入一份删了关键字段的坏文件 → 报可读错误且目录无残留。

**Acceptance Scenarios**:

1. **Given** 一份合法专家文件，**When** 执行导入，**Then** 生成带完整人设的 Agent、立即可用，人设各字段按映射表来自源文件（角色/个性/沟通/关键规则各有其位，记忆/经验进背景、使命/流程进正文、装饰字段丢弃）
2. **Given** 源文件缺少角色字段，**When** 执行导入，**Then** 角色被默认人设「乐于助人的助手」兜底，不产生空字段 Agent
3. **Given** 目标 Agent 名已存在，**When** 执行导入，**Then** 拒绝且不覆盖现有 Agent
4. **Given** 源文件损坏（缺少必需字段），**When** 执行导入，**Then** 报可读的校验错误，且不残留半成品目录（回滚生效）

### User Story A3 - 管理台看到、改得、导得进 (Priority: P3)

管理台 Agent 详情页新增「人格」卡（只读展示七要素，没写人设的 Agent 不显示该卡）；管理员可在线编辑人格（改一个字段，下一轮对话立刻生效，无需重启）；导入走两步——先「预览」看渲染出的完整 Agent 定义 + 校验状态行（此时不产生任何落盘副作用），确认后「落盘」。

**Why this priority**: 入口让机制和内容可操作——人格卡是读、编辑端点是写、导入是「粘贴即预览再落盘」。三入口共享同一条校验链，是让非命令行用户也能用上人格与导入的关键。

**Acceptance Scenarios**:

1. **Given** Agent 已写人设，**When** 打开详情页，**Then** 看到「人格」卡且七要素投影完整；未写人设的 Agent 不显示该卡
2. **Given** 管理员把某 Agent 的性格从「严谨」改成「随和」，**When** 保存并再发起对话，**Then** 下一轮会话即用新人格，且正文与其他配置原样保留（不受编辑影响）
3. **Given** 管理员粘贴一份专家源文件做导入，**When** 先预览，**Then** 看到渲染出的完整 Agent 定义 + 校验状态，但磁盘上没有新 Agent 目录（预览不落盘）
4. **Given** 预览确认无误，**When** 点确认落盘，**Then** 新 Agent 目录出现、详情页可读回人格卡
5. **Given** 源文件无法派生 Agent 名（如缺 name 且无显式名称），**When** 预览，**Then** 返回明确错误而非静默落盘

---

## Part B — 人格库（copy-in 模板库）与导入预览真实校验

### User Story B1 - 人格库：想用的人格里存得下、改得了、删得掉 (Priority: P1)

管理员把反复用到的 persona（团队自己的审查员、公司风控风格等）存进人格库：「保存为自定义」→ 下次导入直接在库列表里选，不再翻文件夹贴 .md。**内置 12 个专家人格随 jar 升级自动更新、永远只读**（不播种、不搬进工作区）；自定义人格保存在工作区 `.oryxos/personas/`，跨版本持久、可改可删。选中任意人格导入 = **照搬源文件原文复制进 Agent（copy-in）**，不是按名引用——改了库里的人格，不影响已经导入的 Agent。

**Why this priority**: Part A 导入把内容接进来了，但没有「留得住、复用得起」的载体——复制一份源文件只在当前 Agent 生效，换一个 Agent 又要重新贴。人格库是「内容资产」闭环的收口。内置只读 + 自定义 CRUD 的分界，正面回答了「版本升级时 12 默认人格会不会携带」的疑虑：**内置永远来自 jar（升级自动更新），工作区只放用户自建，两者存储分开、列表合并**。

**Independent Test**: 保存一个自定义人格 → 列表出现且带「自定义」标记 → 重启 `oryxos serve` 仍在 → 改内容再保存生效 → 删除后从列表消失。内置人格一律无编辑/删除入口。

**Acceptance Scenarios**:

1. **Given** 管理员在左侧「人格库」页填了 key + 粘贴源文件，**When** 点「创建」，**Then** 列表出现新卡片（自定义标记）；到 Agent 页「从人格库导入」即可选到它
2. **Given** 库里有自定义人格，**When** 重启 OryxOS，**Then** 自定义人格仍在（工作区持久）；内置 12 个始终来自 jar（升级自动更新，不落工作区）
3. **Given** 管理员对内置人格点删除/编辑，**When** 操作，**Then** 被拒（内置只读），不落盘
4. **Given** 自定义人格 key 与内置或已有自定义冲突，**When** 保存，**Then** 拒绝并给出可读错误（400）
5. **Given** 从库选择某人格并导入，**When** 落盘，**Then** 源文件原文被复制进 Agent 定义（copy-in）；后续改库中人格不影响已导入的 Agent

### User Story B2 - 导入预览跑真实校验：坏文件导入前就现形 (Priority: P2)

导入第一步「预览」不再只是渲染：对渲染出的完整 Agent 定义跑一次真实的 `AgentLoader.parse` dry-run（纯内存、不落盘、不注册），把校验结果（可解析 / 缺 name / 缺 provider / YAML 坏）直接显示在预览里；同时补齐 `boundaries`/`sampleStyle` 两个字段的预览。**预览永远返回 200**——校验失败体现在 `validation.valid=false` + 可读 message，而不是 HTTP 400。

**Why this priority**: Part A 的预览只解决「看到渲染结果」，没解决「这个结果能不能被底座解析」。缺 provider 的源文件要等点「导入」之后才报错，体验是断的。dry-run 复用 `agentLoader.parse`（`saveFiles` 写前预校验已用它），不新开校验路径。

**Independent Test**: 预览合法源 → 校验行 ✅ 且显示解析出的 provider/model；预览缺 provider 的坏源 → 校验行 ❌ + 可读 message，HTTP 仍是 200。

**Acceptance Scenarios**:

1. **Given** 一份合法专家源文件，**When** 预览，**Then** 校验行显示 ✅ 可解析，并展示解析出的 provider/model（model 缺省标「占位，导入后可改」）
2. **Given** 源文件缺 provider（或缺 name、YAML 语法坏），**When** 预览，**Then** 校验行显示 ❌ + 具体 message，HTTP 返回 200（不因校验失败而 400）
3. **Given** 预览结果含 boundaries / sampleStyle，**When** 查看，**Then** 两个字段在预览区可见（红线与风格示范落没落对，导入前即知）
4. **Given** 预览校验失败，**When** 不修改直接落盘，**Then** 落盘仍走既有校验链拒绝（预览不 bypass 落盘校验）

---

### Edge Cases

- 人格段缺 `name` / `role` 之一 → 视为坏配置，校验失败并走既有「记 ERROR 跳过」路径，与缺其他必需配置同一口径
- 源文件有 `name` 但目录名期望用 slug——显式指定名称优先，缺省从 displayName 派生合法 slug；派生为空（全中文名）→ 400
- 导入的工具交集结果为空（本机没有任何源文件要求的工具）→ 空工具列表放行（最小 Agent 无工具也可对话）
- 编辑人格时原 frontmatter 无 persona 段 → 从无到有新建该段
- 并发：同一 Agent 名同时被导入两次 → 以先到者为准，后者拒绝
- 管理台预览传入超大/异常内容 → 解析失败报错，不落盘不崩溃
- 自定义人格文件被手工删掉/残留同名目录 → `PersonaStore` 检出、列表剔除、不崩溃
- 内置人格 key 与自定义 key 撞名 → 自定义创建被拒（内置优先）

## Requirements *(mandatory)*

### Part A — persona 结构化承载与导入

- **FR-001**: Agent 配置可承载结构化人设，固定七要素（名字、角色、性格、语气、准则、边界、风格示范）；名字与角色必填，其余可空，风格示范可空
- **FR-002**: 凡配置了人设的 Agent，每轮系统提示必须注入人格段，位置固定（身份描述之后、任务指令之前）、格式固定、内容来自当前配置文件（不缓存）
- **FR-003**: 未配置人设的 Agent 完全不注入人格段（含其锚点），行为与升级前一致；不允许为老 Agent 造默认人格
- **FR-004**: 导入专家文件时，人设各字段按映射表从源文件提取：身份/角色→角色、个性→性格、沟通→语气、关键规则→准则（红线细分暂不做，全进准则）、记忆/经验→背景知识（不属于人设字段）、使命/交付物/流程/指标→任务正文；装饰字段（emoji/color）丢弃
- **FR-005**: 导入的 Agent 与手工建 Agent 走同一道校验——先校验后落盘、失败回滚、重名拒绝不覆盖；坏文件报可读错误且不残留半成品
- **FR-006**: 导入时对缺失做兜底：源文件角色为空→默认人设「乐于助人的助手」；本机不存在的工具被剔除（取交集）；供应商缺失→默认供应商
- **FR-007**: 管理台 Agent 详情提供人设只读卡（无则隐藏）；提供编辑入口，只改人设段、正文与其他配置原样保留，人设必需字段缺失时拒绝保存
- **FR-008**: 管理台提供导入「预览」与「落盘」两步——预览渲染完整 Agent 定义但不产生任何持久化副作用；落盘后才创建新 Agent
- **FR-009**: 导入 Agent 落盘后立即被现有热加载/列表机制感知，无需重启即可对话
- **FR-010**: sample_style（YAML 键 `sample_style`）作为语气锚点注入（1~2 句示例回复），不渲染成「必须逐条做到」的规则清单

### Part B — 人格库与预览校验

- **FR-011**: 提供人格库工作区目录 `.oryxos/personas/`（扁平 `<key>.md`，key 只允许字母/数字/下划线/连字符）；内置 12 个人格永远留在 classpath（jar）只读，**不播种、不搬进工作区**——升级自动携带，且永不 merge 进用户自建
- **FR-012**: 人格库 CRUD——列表（内置 + 自定义合并，带 builtin 标记）、详情（源文件全文）、新建、更新、删除；删除 = 物理删除（persona 无反向引用：copy-in 保证 Agent 只持有复制内容）
- **FR-013**: 内置人格一律拒绝增删改（只读），返回 400 + 可读 message
- **FR-014**: 自定义 key 与内置 key 或已有自定义 key 同名 → 400；key 非法（路径穿越/中文/空）→ 400
- **FR-015**: 列表合并顺序：内置（固定 preset 顺序）在前、自定义（字典序）在后；卡片 meta（label/description/emoji）从源文件 frontmatter 提取，内置额外带 `sourceFile` 署名
- **FR-016**: 从人格库导入 = copy-in：选中即把源文件原文复制进 Agent 定义（走 import-preview → import → saveFiles 链）；**仍不做按名引用/共享人格实体（人格市场仍是红线，见 Input 红线节）**
- **FR-017**: import-preview 对渲染出的完整 Agent 定义跑真实 `AgentLoader.parse` dry-run（纯内存、不落盘、不注册）；校验结果（valid + message + 解析出的 provider/model）随预览返回
- **FR-018**: import-preview 永远返回 200——校验失败体现在 `validation.valid=false` + 可读 message，不以 400 呈现（bad input 语义由落盘校验把关，不 bypass）
- **FR-019**: 预览补齐 `boundaries`/`sampleStyle` 两个字段（Part A 渲染时被丢弃，现在解析时即见）
- **FR-020**: 管理台左侧导航**单列「人格库」页**——内置 + 自定义合并列表、自定义新建/编辑/查看/删除（编辑改源全文、key 不可改；内置只读、仅可查看）；**Agent 新建「从人格库导入」界面只做纯选择**（选中 → 拉源全文作导入草稿）；Agent 详情「人格」卡（7 字段展示 + 编辑）；预览区校验状态行 + boundaries/sampleStyle 展示

### Key Entities

- **Persona（人设）**：Agent 的结构化人格定义，七要素固定；名字/角色必填，其余可空；是 Agent 一等配置（与工具、供应商、设置平级），区别于身份描述的自由文本——`Profile.Persona`（Java 驼峰 ↔ YAML 下划线，`sample_style`→`sampleStyle`）
- **ParsedExpert（源专家）**：一份专家文件解析后的中间形态——frontmatter 头 + 按 `##` 标题关键词分类的段落（身份/记忆、核心使命、关键规则、技术交付物、工作流程、沟通风格、成功指标）
- **Agent 定义（AGENT.md）**：导入产物——frontmatter（含 persona 段、工具交集、供应商兜底、渠道）+ 任务正文（使命/交付物/流程/指标）
- **PersonaEntry**：人格库统一入口记录——key、label、description、emoji、sourceFile（仅内置）、builtin 标记
- **PersonaService / PersonaStore / PersonaPresetCatalog**：编排只读内置（classpath preset）+ 可 CRUD 自定义（`.oryxos/personas/`），两者存储分开、列表合并；`PersonaStore` 路径经 `RealPathBoundary` 约束在工作区内（oryxos-persona 模块）
- **AgentValidation**：dry-run 结果——`Profile` + error，`valid() = error == null`（`AgentLifecycleService.validateAgent` 产出）
- **ValidationView**：预览里的校验投影——valid、message、provider、model

## Success Criteria *(mandatory)*

### Part A — 可测成果

- **SC-001**: 任何写有人设的 Agent，多轮对话中人格段出现位置 100% 固定（身份之后、任务之前）、字段渲染 100% 完整
- **SC-002**: 存量未写人设的 Agent 升级后行为零改变（系统提示不含人格段）
- **SC-003**: 导入一份合法专家文件后，Agent 立即可用（无需重启/手工干预）；同一 Agent 名重复导入 100% 被拒绝且不覆盖
- **SC-004**: 导入校验失败时 100% 报可读错误、0 个半成品目录残留（回滚全量生效）
- **SC-005**: 管理台可完成「看人格卡 → 改人格 → 预览导入 → 落盘」全流程；预览阶段 0 持久化副作用（磁盘无新目录、导入未被触发）
- **SC-006**: 编辑人格只影响人设段——正文与其余配置 100% 原样保留
- **SC-007**: 通过导入创建的所有 Agent 100% 带有效人设（名字/角色非空，其余字段可空但结构完整）

### Part B — 可测成果

- **SC-008**: 人格库列表恒为「12 内置 + N 自定义」，自定义 CRUD 全通，内置增删改 100% 被拒
- **SC-009**: 自定义人格跨重启持久（工作区落盘）；内置 12 个不落工作区（升级自动携带）
- **SC-010**: 从人格库选任一内置/自定义导入 = 源文件原文被复制进 Agent（copy-in），Agent 与库内容解耦
- **SC-011**: import-preview 100% 返回 200；合法源校验 valid=true 且带解析出的 provider/model，非法源（缺 name/provider、YAML 坏）校验 valid=false + 可读 message
- **SC-012**: 预览区展示 boundaries/sampleStyle，与 Part A 导入映射一致

## Assumptions

- 导入输入为本地文件路径或粘贴内容，不支持网络 URL 拉取（网络/缓存/版本锁定留待后续）
- 单文件导入；批量导入（整个专家仓库全部文件）不在本特性范围（目录冲突/进度/部分失败回滚待真实场景出现再做）
- 源文件的「关键规则」统一进准则字段，不做红线细分（源文件无干净分隔标记）
- 人设为运行时恒定字段，不支持运行时切换/多人格并存；不自动从对话学习进化
- **不做「人格市场」与「按名引用共享人格」**（红线，见 Input）；不做与源专家仓库的双向同步（导入即固化，改动由作者主导）
- **不打通 USER.md 用户画像联动，也不做独立 SOUL.md 生成器**（bootstrap 文件与结构化人设两条路，本节不合流）
- **oryxos 生成流不改造**：`ensurePersona`（默认人格兜底）迁入 core 但未接线到 `generateDraft`；本特性只保证「导入」与「人工编辑」路径产出有效人设，不保证「大模型生成」路径（红线外分叉流）
- 依赖 specs/011-agent-lifecycle 已交付的 `AgentLifecycleService.saveFiles`/`agentLoader.parse` 校验链与「先预览后落盘」模式，本节复用而非重建
- 无新增第三方依赖；复用既有 SnakeYAML/Picocli/Spring MVC 框架能力；无新增 SQLite 表（persona 与自定义人格均为文件系统真相源）
