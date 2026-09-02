# Research: Persona 人格 + 人格库（copy-in 模板库）+ Agent 导入

本节为**设计决策固化**（整链设计的裁决记录），每条给出 Decision / Rationale / Alternatives considered。

## 决策 1：persona 是结构化字段，不是「再加一段 prompt」

- **Decision**: 新增 `Profile.Persona` 七字段值对象（name/role/traits/tone/values/boundaries/sample_style），name/role 必填其余可空；`Profile` 组件列表插在 identity 之后。
- **Rationale**: 结构化给「恒定」——固定模板渲染、每轮固定注入、可校验、可被导入器/Web 表单逐字段读写；自由文本给「补充」但随文本漂、无校验、复制靠抄。
- **Alternatives considered**: 继续用 `identity.prompt` 自由散文（否决——无格式保证）；独立 SOUL.md（否决——bootstrap 文件与结构化人设两条路）。

## 决策 2：人格恒定 + 缺省零改变注入

- **Decision**: `ContextLoader` 在 identity.prompt 之后、AGENT.md 正文之前注入固定模板渲染的人格段（锚点「## 你的人格（每轮固定，不可违背）」）；每轮重读当前文件，无缓存。frontmatter 无 `persona:` → 不注入该段、不含锚点。
- **Rationale**: 身份先于任务——模型先立住人设再干活；「每轮一模一样地出现」是 persona 价值的机械保证；老 Agent 升级后行为逐字节一致，不允许「为统一造默认人格」。
- **Alternatives considered**: 让 `PromptBuilder` 拼装（否决——persona 已由 ContextLoader 注入 system，不进会话历史）；给老 Agent 自动补默认人格（否决——改变存量行为）。

## 决策 3：导入走 saveFiles 而非 create

- **Decision**: `importAgent(name, markdown)`：name 冲突显式拒绝（镜像 create），委托 `saveFiles(name, Map.of("AGENT.md", markdown))`——先 `agentLoader.parse` 校验再落盘、失败回滚、注册后热加载。
- **Rationale**: 导入产物与手工建 Agent 走同一道校验链，不新开校验路径；「先预览后落盘」镜像既有 generate-files/saveFiles 模式。
- **Alternatives considered**: 复用 create（否决——create 是脚手架模板+空描述，import 是映射后的完整 markdown）。

## 决策 4：parser/importer 纯 POJO，与 persona 字段解耦可并行先写

- **Decision**: `AgencyAgentsParser`（`.md` → `ParsedExpert`）、`AgencyAgentsImporter`（`ParsedExpert` + defaultProvider + availableTools → AGENT.md 字符串）均零 Spring、无 IO、不 import 任何人格实现类。
- **Rationale**: 纯函数式转换，`@TempDir` + 内存字符串可测干净；不感知 persona 字段是否已落地。
- **Alternatives considered**: 解析/组装逻辑混进 AgentLifecycleService（否决——耦合校验链，无法并行）。

## 决策 5：导入映射表是导入器唯一事实源

- **Decision**: frontmatter name → identity.agent_name + persona.name；description → description；源文件名 slug → 目录名；角色/个性/沟通/关键规则 → persona.role/traits/tone/values（红线细分先不做，全进 values）；记忆/经验 → identity.prompt（背景知识，**不是 persona 字段**）；使命/交付物/流程/指标 → 正文；emoji/color 丢弃。
- **Rationale**: 「这个 Agent 知道什么」≠「这个 Agent 是谁」——背景进身份、行为进人设、任务进正文。
- **Alternatives considered**: 记忆/经验进 persona（否决——七字段无背景位）。

## 决策 6：导入 tools/provider 兜底

- **Decision**: 导入器内置 `DEFAULT_TOOLS = [read_file, shell, notify]`，与本机实际可用工具取交集（幂等，未知 tool 静默剔除）；provider 用 `AgentLifecycleService.defaultProvider()` 兜底（缺省「deepseek」），model 用「请在此填写模型名」占位。
- **Rationale**: 源文件工具无关（frontmatter 无 tools），落地时给专家一套「默认安全集 ∩ 本机可用」的能力；占位模型由用户在管理台补真实值（导入是起点不是终点）。
- **Alternatives considered**: 从源文件读工具声明（否决——源文件 frontmatter 无 tools）。

## 决策 7：persona 编辑走 split→Map→重序列化，不用 replaceTopLevelScalar

- **Decision**: `updatePersona`：`AgentMarkdown.split` → 复制 frontmatter 进可变 Map → `put("persona", personaMap)`（键唯一，put 覆盖即移除旧块）→ 重序列化 → `agentLoader.parse` 预校验 → `update` 落盘。
- **Rationale**: `replaceTopLevelScalar` 只支持顶层标量 `key: value`，persona 是嵌套多行块；重序列化链是既有 `updateBasicInfo` 的同款套路。
- **Alternatives considered**: 正则替换 persona 块（否决——破坏 frontmatter 围栏格式风险高）。

## 决策 8：人格库是 copy-in 模板库，不是人格市场

- **Decision**: 人格库独立成 `oryxos-persona` 模块——内置 12 classpath 只读 + 自定义 `.oryxos/personas/` CRUD；从库导入 = 照搬源文件原文复制进 Agent。**不做按名引用/共享人格实体**。
- **Rationale**: copy-in 与按名引用是两种截然不同的抽象——前者只是把「源文件原文」变成工作区可增删改的载体（模板库），后者才是跨 Agent 共享的人格实体（市场）。「大家都在写同一套人格」的信号出现前，后者不抽象。
- **Alternatives considered**: 按名引用 + 软连接共享人格实体（否决——红线；多 Agent 引用、改一处处处生效的复杂度与升级语义现在没有真实场景支撑）。

## 决策 9：预览跑真实校验（dry-run），永远 200

- **Decision**: `validateAgent(name, rendered)` 对渲染出的完整 AGENT.md 跑真实 `agentLoader.parse`（纯内存、不落盘、不注册）；`ProfileValidationException | YAMLException` → `AgentValidation.fail(error)`，其余 `ok(profile)`。预览 HTTP 永远 200——校验失败体现在 `validation.valid=false` + 可读 message；落盘仍走 `importAgent` 真实校验链，不 bypass。
- **Rationale**: 复用 `agentLoader.parse`（saveFiles 写前预校验已用它）不新开校验路径；「预览永远 200 + body 内表达校验结果」让坏源文件导入前就现形，且不被 HTTP 层误判。
- **Alternatives considered**: 校验失败返回 400（否决——与「预览」语义冲突，前端要区分「请求错」与「内容不可导入」两种失败）。

## 决策 10：管理台四入口职责分离

- **Decision**: 人格库**单列左侧导航「人格库」页**——作者面：新建/编辑/删除/查看（内置只读）；Agent 新建「从人格库导入」是**消费面纯选择**（选中 → 拉源全文作导入草稿，不内嵌保存/删除）；Agent 详情「人格」卡（读 + PUT 编辑 persona 段）。
- **Rationale**: 作者面与消费面各管一段，UI 职责分离；人格卡把 Part A 的「看 → 改」闭环做成可操作入口。
- **Alternatives considered**: 人格管理内嵌在导入页弹框（否决——保存/删除按钮塞进选择卡片，操作路径混乱，用户拍板单列页）。

## 决策 11（边界声明）：generate 流不动

- **Decision**: `ensurePersona`（缺 persona 段用默认人格兜底：name=frontmatter name/「助手」、role=「乐于助人的助手」、「有下限不惊艳」）随导入链迁入 `AgentLifecycleService`，但**不接线**到 oryxos 既有 `generateDraft`。
- **Rationale**: oryxos 的「大模型生成 Agent 定义」是与导入正交的既有分叉流；本特性保证「导入 / 人工编辑 / 人格库」三条路径产出有效人设，不扩散到生成流产物形态。
- **Alternatives considered**: 把 ensurePersona 挂进 generateDraft（否决——改动既有生成流的产物契约，超出本特性范围；用户红线同款）。
