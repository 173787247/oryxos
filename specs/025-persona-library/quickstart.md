# Quickstart: Persona 人格 + 人格库（copy-in 模板库）+ Agent 导入 — 端到端验收走查

自动化部分由全量测试套件承载（`mvn test` 全绿即通过，含 spotless/checkstyle/PMD/spotbugs），本页是**人工端到端验证**场景（Phase 6 冒烟）。

## 前置

- `cd oryxos && mvn test` 全绿（质量门禁无错）
- 已 `oryxos init` 初始化 `.oryxos/` 工作区；有真实 LLM Provider 配置
- 一份 agency-agents-zh 专家源文件，如 `engineering/software-architect.md`

## 场景 1：CLI 手工导入

```bash
oryxos agent import engineering/software-architect.md
```

**预期**：
- 产物 `.oryxos/agents/software-architect/AGENT.md`：
  - `emoji`/`color` 没了（丢弃）
  - 「身份与记忆」拆成 `persona`（角色/个性）+ `identity.prompt`（记忆/经验）
  - 「关键规则」进 `persona.values`
  - 四个任务段（核心使命/技术交付物/工作流程/成功指标）并成正文
  - frontmatter 含完整 `persona:` 七字段
- 再跑同一份文件 → 报「Agent 已存在」（冲突拒绝生效）

## 场景 2：热加载体感

```bash
oryxos serve
```

`serve` 起来后执行一次导入，管理台「Agent」列表立刻看到新目录；`oryxos chat --profile software-architect` 直接聊——语气应带「专业简洁、先结论后依据」的味道，无需重启。

## 场景 3：管理台人格卡 + 表单（Part A）

- Agent 详情「基本信息」页看到「人格」卡（`GET /agents/{name}` 的 `persona` 投影，七字段展示）
- 点「编辑人格」改 traits 从「严谨」改成「随和」→ 保存（`PUT /api/v1/agents/{name}/persona`）→ 下一轮会话立刻换调子（`ContextLoader` 每轮重读，不用重启）；name/role 留空时保存按钮禁用
- 打开一个没写 persona 的老 Agent → 详情页不显示「人格」卡内容（`persona` 为 null，显示「设置人格」入口）

## 场景 4：Web 导入前后对比

管理台 Agent 新建页「从人格库导入」选一个人格，或上传/粘贴一份专家源 .md：
1. 预览 → 看到渲染出的 AGENT.md 全文 + 字段投影 + **校验状态行**（✅ 可导入 + provider/model，或 ❌ + message）；**此时 `.oryxos/agents/` 下还没有这个目录**（预览不落盘）
2. 确认后落盘 → `.oryxos/agents/<slug>/` 出现、详情页可读回人格卡
3. 用中文名专家故意不填 name → 预览明确报「无法从源文件派生 Agent 名」

## 场景 5：人格库页（Part B）

- 左侧导航进入「人格库」页：列表 = 12 内置（只读、可查看）+ 自定义（可新建/编辑/删除），内置带「内置」徽标、自定义带「自定义」
- 「+ 新建人格」填 key + 粘贴源文件 → 列表出现自定义卡片
- 重启 `oryxos serve` → 自定义人格仍在（`.oryxos/personas/<key>.md` 落盘持久）
- 编辑改源全文（key 不可改）→ 保存生效；删除 → 从列表消失（物理删）
- 对内置人格点编辑/删除 → 被拒（内置只读）
- 改一个库里的人格，检查此前从它导入的 Agent **不受影响**（copy-in 生效）

## 场景 6：预览校验（坏文件导入前现形）

- 预览一份缺 provider 的源 → 校验行 ❌ + 可读 message，HTTP 仍是 200
- 不修改直接落盘 → 落盘仍被既有校验链拒绝（预览不 bypass）

## 场景 7：校验生效（回滚）

手改一份源文件把 frontmatter 的 `name` 删掉，导入——确认报可读的校验错误、且 `.oryxos/agents/` 下没有残留半个目录（回滚生效）。

## 契约快速对照

端点契约见 [contracts/persona-api.md](./contracts/persona-api.md)；persona 值对象/导入产物/人格库文件模型见 [data-model.md](./data-model.md)。
