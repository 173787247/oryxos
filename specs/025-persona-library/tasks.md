# Tasks: Persona 人格 + 人格库（copy-in 模板库）+ Agent 导入

**Input**: Design documents from `/specs/025-persona-library/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: 契约测试随实现落地（harness 先行）——每个故事相内测试任务排在实现之前；本清单任务已全部随分支完成（实现 + 测试），标记为 [x]。

**路径约定**: Maven 多模块（14 模块含新 `oryxos-persona`）；测试类扩展既有类（`ProfileLoaderTest`/`ContextLoaderTest`/`AgentLifecycleServiceTest`/`AgentApiControllerTest`）+ 新建 `AgencyAgentsParserTest`/`AgencyAgentsImporterTest`/`PersonaPresetCatalogTest`/`PersonaPresetsGoldenTest`/`PersonaServiceTest`/`PersonaStoreTest`/`PersonaApiControllerTest`。

---

## Phase 0: Setup

本节**无 Setup** 侧重量级前置：不新增表（persona 是 frontmatter 结构化段、自定义人格是文件）、无新增第三方依赖（复用 SnakeYAML/Picocli/Spring MVC）。但新增**一个模块** `oryxos-persona`（人格库机制，理由见 plan.md「模块拆分」）。

---

## Phase 1: Foundational（阻塞前置——`Profile` record 加 persona 组件）

**Purpose**: `Profile` 加组件是编译级破坏，必须先落地且全调用点补齐，后续一切才可编译。

- [x] T001 `Profile.Persona` 嵌套 record + 组件插入：`oryxos-core/src/main/java/io/oryxos/core/profile/Profile.java`——新增七字段 record（name/role 必填语义由校验层保证，record 本身可空），`persona` 组件插在 `identity` 之后；旧 11 参兼容构造器加 `null` 透传
- [x] T002 全部 `new Profile(...)` 调用点补 `null`：生产（`AgentLifecycleService.generateDraft`、`ProfileLoader.toProfile`）+ 测试各 `profile()` 助手——机械改动，`identity` 后补一个 `null`，不改任何语义

**Checkpoint**: 编译通过；老 frontmatter 解析后 `profile.persona()` 为 null（缺省零改变）。

---

## Phase 2: Part A - 结构化 persona + 导入链（US A1/A2，P1/P2）

**Goal**: persona 结构化承载 + 固定注入 + 缺省零改变；agency-agents-zh 专家文件 → parser/importer 纯 POJO 转换 → `importAgent` 走 saveFiles 同一道校验链 → 热加载即用；重名拒绝、失败回滚。

### Tests

- [x] T003 [P] [A] `ProfileLoaderTest` persona 解析契约：七字段解析正确（含 `sample_style`→`sampleStyle`）/ 无 persona 段返回 null / 缺 name/role → `ProfileValidationException` / `sample_style` 可空
- [x] T004 [P] [A] `ContextLoaderTest` 注入位置 + 缺省零改变：有人格时注入固定模板「## 你的人格（每轮固定，不可违背）」且位置在 identity.prompt 之后、AGENT.md 正文之前；无 persona 时 system 不含该锚点；模板字段逐项渲染
- [x] T005 [P] [A] `AgencyAgentsParserTest`：frontmatter name/description 解析正确；`##` 关键词分类；记忆/经验进 background 不进 persona；emoji/color 被忽略
- [x] T006 [P] [A] `AgencyAgentsImporterTest`：frontmatter 组装（BLOCK dump 可被 `AgentMarkdown.split` 无损拆回）；persona 七字段结构正确 + role 空兜底「乐于助人的助手」；traits/tone/values 非空才写；tools 交集；provider 用 defaultProvider 兜底
- [x] T007 [P] [A] `AgentLifecycleServiceTest`：importAgent 合法 markdown 落盘 + 注册 + 可读回；已存在 name → 冲突拒绝不覆盖；updatePersona 只改 persona 段（正文与其余 frontmatter 保留）

### Implementation

- [x] T008 [A] `ProfileLoader.toPersona`：`toProfile` 在 `toIdentity` 后调用 `toPersona(asMap(map.get("persona")))`；无段返回 null；name/role 缺失抛 `ProfileValidationException`；复用 `asString`/`asMap` 空安全解析；`sample_style`→`sampleStyle`
- [x] T009 [A] `ContextLoader.renderPersona` + `appendPersonaField`：identity.prompt 之后、正文之前注入固定模板人格段（名字/角色/性格/语气/准则/边界/风格示范）；persona null 时不注入；`PromptBuilder` 一行不改
- [x] T010 [A] `AgencyAgentsParser`：纯 POJO、零 Spring、无 IO；frontmatter 解析（复用 `AgentMarkdown.split`）+ `##` 标题关键词分类 → `ParsedExpert`；emoji/color 忽略
- [x] T011 [A] `AgencyAgentsImporter`：纯 POJO；`toMarkdown(...)` frontmatter 用 SnakeYAML `DumperOptions` BLOCK 流式 dump（镜像 `assembleMarkdown`）；persona role 空兜底、非空才写；tools = `DEFAULT_TOOLS=[read_file, shell, notify]` ∩ availableTools；provider = defaultProvider 兜底 + model 占位；identity.prompt = 角色打头 + 记忆/经验背景；不生成 scripts/REFERENCE/output
- [x] T012 [A] `AgentLifecycleService.defaultProvider()` + `importAgent(name, markdown)` + `updatePersona(name, persona)` + `ensurePersona(markdown)`（迁入但未接线）+ 私有 `personaMap`/`defaultPersona`：冲突先拒 → `saveFiles` 走真实校验链；updatePersona 用 split→put persona 键→重序列化→parse 预校验→update

**Checkpoint**: MVP 成立——手工写 persona 的 Agent 每轮恒定、老 Agent 零改变；CLI/Web 导入一个专家即可对话、冲突拒绝、坏文件回滚。

---

## Phase 3: Part A - 管理台三入口 + 人格卡补齐（US A3，P3）

**Goal**: AgentView 人格卡投影 + PUT 编辑 persona 段 + Web 导入两步走；**补齐 oryxos Agent 详情人格卡缺口**（此前 `AgentView` 无 persona 投影、`updatePersona` 端点存在但响应丢 persona）。

### Tests

- [x] T013 [P] [A] `AgentApiControllerTest`：GET 详情含 persona 投影（null 时无卡）；`PUT /persona` 编辑转发 + name/role 缺失 400 + Agent 不存在 404；`import-preview` 返回 `ImportPreviewView` 且不触发 importAgent；`import` 落盘返回 AgentView；**新增 updatePersona_success 断言 persona 七字段投影 + updatePersona 404**

### Implementation

- [x] T014 [A] `AgentView.PersonaView` 投影 + persona 组件：`oryxos-web/.../dto/AgentView.java`——新增 `PersonaView` 嵌套 record（七字段）+ `persona` 组件；`from()`（唯一构造点）加 `PersonaView.from(p.persona())`，`from(null)` 返回 null（老 Agent 不显示人格卡）
- [x] T015 [A] `UpdatePersonaRequest` + `PUT /{name}/persona`：请求体七字段 → `Profile.Persona` → `lifecycle.updatePersona` → `ApiResponse.ok(view(...))`；缺 name/role → 400
- [x] T016 [A] `ImportAgentRequest` + `ImportPreviewView` + 两个导入端点：`POST /import-preview`（解析渲染不落盘）、`POST /import`（importAgent 落盘返回 AgentView）；`resolveImportName` 显式优先、缺省 slug 派生、派生为空 400
- [x] T017 [A] 前端接线：Agent 详情「人格」卡展示（persona 投影，null 时显示「设置人格」）+ personaEdit 表单（PUT /persona，name/role 必填提示）；创建页「从 agency-agents-zh 导入」两步走（预览全文 + 确认落盘）

**Checkpoint**: 管理台看得到（卡）、改得了（PUT 只动 persona 段）、导得进（预览不落盘 → 确认落盘）。

---

## Phase 4: Part B - 人格库（copy-in 模板库）+ 预览真实校验（US B1/B2，P1/P2）

**Goal**: 新模块 `oryxos-persona`（内置 12 classpath 只读 + 自定义 CRUD）；`import-preview` 跑真实 `AgentLoader.parse` dry-run（纯内存、不落盘、不注册），永远 200；预览补齐 boundaries/sampleStyle；管理台左侧导航单列「人格库」页。

### Tests

- [x] T018 [P] [B] `PersonaStoreTest`：写/读/列/存在（每 key = `personas/<key>.md`）；同名自定义覆盖；删除物理删；读不存在 key 抛错；非法 key 拒绝（路径穿越/中文/空/null）；`entryExists` 检出同名目录残留
- [x] T019 [P] [B] `PersonaServiceTest`：list 合并（内置 builtin=true + 自定义，带 frontmatter meta 投影）；create 与内置/自定义同名冲突拒绝；update 内置只读拒绝 / 自定义可改；delete 内置只读拒绝 / 自定义物理删；source 自定义优先回落到内置源
- [x] T020 [P] [B] `PersonaPresetCatalogTest` + `PersonaPresetsGoldenTest`：12 内置齐备、label/description/emoji/sourceFile 署名；golden 快照逐字节防 CRLF/意外改写
- [x] T021 [P] [B] `PersonaApiControllerTest`：standalone MockMvc + `GlobalExceptionHandler`；列表/详情/新建/更新/删除全通；内置 400、未知 404
- [x] T022 [P] [B] `AgentLifecycleServiceTest` validateAgent 契约：真实 `AgentLoader` 构建 service（不 mock parse）；合法 MD → ok 带派生 Profile；缺 name / 缺 provider / YAML 坏 → fail 带可读 message 不抛异常
- [x] T023 [P] [B] `AgentApiControllerTest` import-preview 校验：合法源 → `validation.valid=true` + provider/model + expert.boundaries/sampleStyle 存在；校验失败 → 仍 200 + valid=false + message

### Implementation

- [x] T024 [B] `oryxos-persona` 模块 + 机制迁移：新建 `oryxos-persona/pom.xml`（parent io.oryxos:oryxos, 依赖 core）+ 根 pom modules/dependencyManagement 登记——`PersonaPresetCatalog`/`PersonaService`/`PersonaStore` + `resources/personas/*.md`(12) + `src/test/resources/personas-golden/`(12) + 4 个测试，包 `io.oryxos.persona`；`PersonaStore` 用 `RealPathBoundary` 约束 + `safe(key)` 白名单
- [x] T025 [B] `AgentValidation` + `AgentLifecycleService.validateAgent(name, markdown)`：`try { ok(agentLoader.parse(...)) } catch (ProfileValidationException | YAMLException e) { fail(e.getMessage()) }`；纯内存不落盘
- [x] T026 [B] `PersonaApiController` + DTO 四件套：`GET /personas`（合并列表）、`GET /{key}`（详情+源全文）、`POST`（新建）、`PUT /{key}`（更新）、`DELETE /{key}`（物理删）；统一 `ApiResponse` 信封
- [x] T027 [B] `ImportPreviewView` 加校验投影：`ImportExpertView` 补 `boundaries`/`sampleStyle`；新增 `ValidationView`（valid/message/provider/model，`from(AgentValidation)`）；`AgentApiController.importPreview` 渲染后追加 `validateAgent` dry-run
- [x] T028 [B] Spring 装配：`OryxOsRuntime` 加 `@Bean personaPresetCatalog()`/`personaStore()`/`personaService(catalog, store)`；`oryxos-cli`/`oryxos-boot` pom 加 persona 依赖
- [x] T029 [B] 前端「人格库」页 + Agent 导入纯选择化：`App.vue` TOP_NAV 加 `personas` + 独立「人格库」页（内置只读查看 + 自定义新建/编辑/删除，源全文编辑弹框）；Agent 新建「从人格库导入」只做纯选择（选中 → 拉源全文作导入草稿）；预览区校验状态行 + boundaries/sampleStyle 展示；`npm run build` 通过

**Checkpoint**: 人格库闭环成立（存得下/跨重启在/改得了/删得掉/内置 100% 只读）；坏文件导入前现形（预览 200 + valid=false + 可读 message）。

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: 全量门禁与跨节契约回归。

- [x] T030 全量 `mvn test` 回归：含 spotless/checkstyle/PMD（targetJdk=17，避开 Java 18+ 语法）/spotbugs + 前序节全部测试绿（跨节契约证据：Profile record 加组件不破坏既有调用点；AgentView 加 persona 组件构造点收敛在 from()）
- [x] T031 H4 六条不变量自查：①涉外 IO 过 Sandbox ②LLM/工具调用落审计表 ③grep 无明文 key ④`session_id` 只在 `SessionManager` 拼 ⑤无 Reactor/CompletableFuture/自建线程池 ⑥无 Spring AI 自动工具执行路径——①②本节无新 Tool/LLM/涉外 IO 路径（导入/人格库是文件与内存操作）N/A；③④⑤⑥ 无明文 key / 无 session_id 拼接 / 无异步与线程池 / 无 Spring AI 自动工具执行 ✓
- [ ] T032 按 quickstart.md 走人工验收 + 端到端冒烟（`oryxos serve` 起：列表 12 内置、自定义 CRUD、import-preview 合法/非法、import 落盘 AGENT.md 含 persona 段、管理台「人格库」页 + Agent 人格卡、CLI agent import）——**留待用户 review 前人工执行，证据补进 acceptance-report.md**

---

## Dependencies & Execution Order

- **Phase 1（Profile 组件）**: 无前置，但 T002 必须完成才可编译
- **Part A（Phase 2/3）**: 依赖 Phase 1（persona 字段先落地，导入器才有目标字段可写）
- **Part B（Phase 4）**: 依赖 Phase 3 端点迁移（PersonaApiController 独立）+ `validateAgent`；人格库 store/service 纯文件操作可与 A 并行
- **Polish（Phase 5）**: 依赖全部故事完成；T032 端到端需真实运行环境（provider 凭证），留待发版前人工执行
