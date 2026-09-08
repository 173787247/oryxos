# 契约：Persona 展示/编辑、Agent 导入与人格库（Web + CLI）

统一前缀 `/api/v1`，响应信封复用现有 `ApiResponse`（`code`/`message`/`data`/`timestamp`）。认证按底座既有约定（内网无认证，见 Web 契约）。

## Part A — Agent 人格与导入

### REST 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/v1/agents/{name}` | Agent 详情——`data.persona` 为七字段投影，无 persona 时整个字段为 `null`（老 Agent 前端不显示人格卡） |
| `PUT` | `/api/v1/agents/{name}/persona` | 结构化编辑人格段：只重写 AGENT.md frontmatter 的 persona 块，正文与其他配置原样保留。name/role 缺失 → 400；Agent 不存在 → 404 |
| `POST` | `/api/v1/agents/import-preview` | 从粘贴的源文件内容预览导入产物：解析 + 渲染 + **真实 `agentLoader.parse` dry-run 校验**，不落盘、不注册。**永远 200**——校验失败体现在 `validation.valid=false` |
| `POST` | `/api/v1/agents/import` | 从粘贴的源文件内容导入并落盘：解析 + 渲染 → `importAgent`（走 saveFiles 校验链）→ 返回 AgentView |

### 请求/响应体（DTO）

#### `UpdatePersonaRequest`（PUT /agents/{name}/persona）

```json
{ "name": "老张", "role": "运维专家", "traits": "严谨", "tone": "简洁", "values": null, "boundaries": null, "sampleStyle": null }
```

- 七字段直出（camelCase，`sampleStyle`↔ YAML `sample_style`）；缺 name/role → 400（`IllegalArgumentException`）

#### `ImportAgentRequest`（POST /agents/import 与 import-preview 共用）

```json
{ "sourceContent": "---\nname: 软件架构师\n...", "name": "architect" }
```

- `sourceContent` 必填（空 → 400）
- `name` 可选：显式优先；缺省从 displayName 派生合法 slug（剔除 `[^A-Za-z0-9_-]`），派生为空（如全中文名）→ 400「无法从源文件派生 Agent 名，请显式提供 name」

#### `ImportPreviewView`（import-preview 响应）

```json
{
  "name": "architect",
  "agentMarkdown": "---\n...全文...\n---",
  "expert": {
    "displayName": "软件架构师", "description": "...", "role": "...", "traits": "...",
    "background": "...", "communication": "...", "keyRules": "...", "boundaries": "...",
    "sampleStyle": "...", "body": "..."
  },
  "validation": { "valid": true, "message": null, "provider": "deepseek", "model": "请在此填写模型名" }
}
```

- `expert.boundaries`/`expert.sampleStyle`：导入前即见红线与风格示范落位
- `validation`（`ValidationView`）：`valid=true` 带解析出的 provider/model；`valid=false` 带可读 `message`（缺 name/provider、YAML 坏），provider/model 为 null——**此时 HTTP 仍为 200**

#### Agent 详情 GET /agents/{name} 的 persona 投影

```json
{ "data": { "name": "ops", "...": "...", "persona": { "name": "老张", "role": "运维专家", "traits": "严谨", "tone": "简洁", "values": null, "boundaries": null, "sampleStyle": null } } }
```

- 无 persona → `"persona": null`

## Part B — 人格库

### REST 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/v1/personas` | 列表——内置（固定 preset 顺序，builtin=true）+ 自定义（字典序，builtin=false）合并 |
| `GET` | `/api/v1/personas/{key}` | 详情——卡片 meta + `sourceContent` 源文件全文；未知 key → 404 |
| `POST` | `/api/v1/personas` | 新建自定义——`{ key, sourceContent }`；与内置/已有自定义同名 → 400（内置只读）；key 空/非法 → 400 |
| `PUT` | `/api/v1/personas/{key}` | 更新自定义——仅 `sourceContent`（key 走路径，不可改）；内置 → 400；未知 → 404 |
| `DELETE` | `/api/v1/personas/{key}` | 删除自定义（物理删）——内置 → 400；未知 → 404 |

### 请求/响应体

- `CreatePersonaRequest`: `{ "key": "my-reviewer", "sourceContent": "---\n..." }`
- `UpdatePersonaLibraryRequest`: `{ "sourceContent": "---\n..." }`
- `PersonaLibraryView`（列表卡片）: key / label / description / emoji / sourceFile（仅内置）/ builtin
- `PersonaDetailView`（详情）: 卡片 + sourceContent

## CLI 契约

```
oryxos agent import <源文件路径.md> [--name <Agent 名>]
```

- **重命令**：必须起 Spring（拿 `AgentLifecycleService`/`ProfileRegistry`/`AgentStore` 一整套 bean 走真实校验链），照抄 `ChatCommand` 的重命令启动模式
- **输入**：本地文件路径（先只支持本地；`--all` 批量、URL 源均不做）
- **目录名**：`--name` 显式优先；缺省从**文件名**取 slug（英文名天然合法）
- **产物**：`.oryxos/agents/<slug>/AGENT.md`，落盘即被热加载；重复导入 → 报「Agent 已存在」（冲突拒绝不覆盖）；坏源文件 → 报可读校验错误 + 回滚无残留
- **成功输出**：落盘路径 + 派生 Agent 名（含提示「provider.model 为占位，请在管理台补真实模型」——导入是起点不是终点）

## 行为契约（实现必须遵守）

1. **人格恒定**：有 persona 的 Agent，每轮 system prompt 人格段位置固定（identity.prompt 之后、正文之前）、格式固定、内容来自当前文件（`ContextLoader` 每轮重读不缓存）
2. **缺省零改变**：无 persona 段 → 不注入、不含「你的人格」锚点，行为与升级前一致；不允许造默认人格
3. **导入必带 + 兜底**：导入 role 空兜底「乐于助人的助手」（有下限、不惊艳）；生成流不保证（`ensurePersona` 未接线，红线外）
4. **同一道校验**：导入走 `saveFiles`——`agentLoader.parse` 先校验再落盘、失败回滚、name 冲突拒绝；坏文件不写坏目录
5. **预览不落盘 + dry-run**：`import-preview` 只跑 parser + importer + `validateAgent`（真实 parse 纯内存），不触发 `importAgent`；预览永远 200，不 bypass 落盘校验
6. **tools 交集**：导入落地工具 = 内置默认安全集 `[read_file, shell, notify]` ∩ 本机实际可用（未知 tool 静默剔除，幂等）
7. **sample_style 是锚点不是清单**：1~2 句示例回复做 few-shot 锚点，不渲染成「必须逐条做到」的规则列表
8. **人格库 copy-in**：从库导入 = 照搬源全文复制进 Agent，不做按名引用（人格市场红线）；自定义人格物理删（无反向引用）
9. **内置只读**：`personas` 端点与「人格库」页对内置 12 个一律拒绝增删改（400），只读查看
10. **路径安全**：`PersonaStore` 的 key 只允许 `[A-Za-z0-9_-]`，读写经 `RealPathBoundary` 约束在 `.oryxos/personas/` 内
