# Data Model: Persona 人格 + 人格库（copy-in 模板库）+ Agent 导入

本特性**无新增 SQLite 表**——persona 是 `AGENT.md` frontmatter 结构化段、自定义人格是 `.oryxos/personas/` 扁平文件，均为文件系统真相源。

## Part A 值对象

### Persona（人设）——`Profile.Persona` 嵌套 record（`io.oryxos.core.profile.Profile`）

- **真相源**: `AGENT.md` frontmatter 的 `persona:` 段（YAML 块）
- **字段**（Java 驼峰 ↔ YAML 下划线，`sample_style`→`sampleStyle` 在 `ProfileLoader` 层完成）：

| 字段 | YAML 键 | 必填 | 说明 |
|------|---------|------|------|
| `name` | `name` | ✅ | 人格名（如「老张」） |
| `role` | `role` | ✅ | 角色定位（如「运维专家」） |
| `traits` | `traits` | 可空 | 性格特征 |
| `tone` | `tone` | 可空 | 语气风格 |
| `values` | `values` | 可空 | 行为准则 |
| `boundaries` | `boundaries` | 可空 | 边界 |
| `sampleStyle` | `sample_style` | 可空 | 风格示范：1~2 句示例回复（语气锚点不是规则清单） |

- **位置**: record 组件插在 `identity` 之后（组件位置 3）——record 加组件是编译级破坏，全部 `new Profile(` 调用点补 `null`（生产 2 处 + 测试若干）；旧 11 参兼容构造器透传 null（老调用点零改动）
- **校验**: 无 persona 段 → `Persona` 为 null（缺省零改变）；有段缺 name/role → `ProfileValidationException`（与缺 name/provider 同一异常同一消息口径），坏 frontmatter 走既有「记 ERROR 跳过」路径

### ParsedExpert（源专家解析中间态）——`AgencyAgentsParser.ParsedExpert`

- **来源**: agency-agents-zh 专家 `.md` 内容（字符串入参，parser 无 IO）
- **字段**: `displayName`（frontmatter name）、`description`、`role`、`traits`、`background`（记忆/经验）、`communication`、`keyRules`、`body`（使命/交付物/流程/指标拼接的任务层）
- **关键词分类**（`##` 标题）: 「你的身份与记忆」→ role/traits/background；「沟通风格」→ communication；「关键规则」→ keyRules；「核心使命 / 技术交付物 / 工作流程 / 成功指标」→ body；其余标题段落归 body
- **忽略**: frontmatter `emoji`/`color`（装饰字段，无承载）

## Part B 文件模型

### 自定义人格库（`.oryxos/personas/`）

- **形态**: 扁平 `<key>.md`，一个文件 = 一份可复用的源文件（与 classpath 内置预设同构的 agency-agents-zh 风格 `.md`）
- **key 规则**: 只允许 `[A-Za-z0-9_-]+`（与 AgentStore/SkillStore `safe` 同口径，防路径穿越）；中文/路径穿越/空 → `IllegalArgumentException`（400）
- **PersonaEntry**: 统一入口记录——key、label（内置用预设 label、自定义取源 frontmatter name 缺省用 key）、description、emoji、sourceFile（仅内置）、builtin 标记
- **存储分界**: 内置 12 个永远在 classpath（jar）只读（升级自动携带、不播种）；自定义只落工作区 `.oryxos/personas/`；两者**存储分开、列表合并**（内置按固定 preset 顺序在前，自定义按字典序在后）
- **删除语义**: 物理删除——copy-in 保证 Agent 只持有复制内容，无反向引用

## 产物结构

### 导入产物 `AGENT.md`（`AgencyAgentsImporter.toMarkdown` 输出）

```yaml
---
name: <displayName>
description: <description | 兜底「描述这个 Agent 做什么」>
identity:
  agent_name: <displayName>
  prompt: 你是<displayName>，<role>。背景知识与经验：<memory/experience>
persona:
  name: <displayName | 兜底「助手」>
  role: <role | 兜底「乐于助人的助手」>      # role 空兜底
  traits: <traits>                          # 非空才写，避免 dump 出 null
  tone: <communication>
  values: <keyRules>                        # 关键规则暂全进 values
provider:
  name: <defaultProvider | 兜底「deepseek」>
  model: 请在此填写模型名                    # 占位，用户后续补真实模型
tools: <DEFAULT_TOOLS ∩ availableTools>     # 默认安全集 [read_file, shell, notify] ∩ 本机可用
channels:
- cli
settings:
  max_iterations: 10
  max_history_turns: 20
---
<task body: 核心使命/技术交付物/工作流程/成功指标>
```

- **格式**: `---\n` + SnakeYAML BLOCK dump + `---\n\n` + body + `\n`（镜像 `assembleMarkdown`），与 `AgentMarkdown.split` 围栏约定对得上，落盘后能无损拆回
- **不生成** scripts/REFERENCE/output 目录（那是 Agent 脚手架的职责）——一个专家 = 一个 `AGENT.md`，够跑

## 关系与生命周期

- **Profile 组件新增**: `persona` 插在 `identity` 之后；`ContextLoader` 每轮重读当前文件（无缓存），persona 非空 → 在 identity.prompt 之后、正文之前注入固定模板人格段
- **导入生命周期**: 源文件 → parser 解析 → importer 渲染 → `importAgent`（name 冲突拒绝 → `saveFiles` 校验链）→ `.oryxos/agents/<slug>/AGENT.md` → 热加载 → 对话即用
- **从人格库导入（copy-in）**: 选中内置/自定义 → `source()` 取源全文 → 作为导入草稿走 import-preview → import → saveFiles 链；**不是**在 AGENT.md 里引用库 key
- **预览生命周期**: `import-preview` → parser + importer 渲染 → `validateAgent(name, rendered)` 真实 `agentLoader.parse` dry-run（纯内存、不落盘、不注册）→ `ImportPreviewView`（agentMarkdown + expert 投影 + ValidationView）
- **编辑生命周期**: `updatePersona` → split → 覆盖 persona 键（键唯一，put 覆盖即移除旧块）→ 重序列化 → parse 预校验 → update 落盘 → 下一轮 `ContextLoader` 重读即生效（无 persona 段时从无到有新建）
- **唯一性规则**: Agent 名冲突导入显式拒绝，不覆盖用户已改过的同名人设；自定义人格 key 与内置/已有自定义撞名拒绝
- **幂等**: 同一源文件反复导入 → importer 产物逐字节一致（DEFAULT_TOOLS 交集幂等、BLOCK dump 确定性）
