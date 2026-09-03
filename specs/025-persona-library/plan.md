# Implementation Plan: Persona 人格 + 人格库（copy-in 模板库）+ Agent 导入 —— 整链

**Branch**: `feat/025-persona-library` | **Date**: 2026-09-02 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/025-persona-library/spec.md`

## Summary

一条「人设」链一次打通：**结构化 persona 机制**（`Profile.Persona` 七字段 + `ProfileLoader` 解析 + `ContextLoader` 固定注入，契约一/二/四）→ **agency-agents-zh 专家人格导入**（parser/importer 纯 POJO + 映射表 + 走 `saveFiles` 同一道校验链）→ **人格库（copy-in 模板库）**（新模块 `oryxos-persona`：内置 12 classpath 只读 + 自定义 `.oryxos/personas/` CRUD）→ **Web 四入口**（Agent 详情「人格」卡投影 + PUT 编辑 persona 段 + import-preview/import 先预览后落盘 + 独立「人格库」页）。

核心架构判断：persona 是「每轮固定注入的结构化一等配置」而不是「再加一段 prompt」；导入不新开校验路径，搭 specs/011 交付的 `saveFiles`（`agentLoader.parse` 先校验后落盘、失败回滚）那辆现成的车；**人格库是「复制进 Agent 的起点」不是「共享实体」**——copy-in 与按名引用是两种截然不同的抽象，这次只做前者，按名引用的人格市场仍是红线。

**本迁移附带补齐的缺口**: oryxos 侧 Agent 详情页此前没有 persona 卡（`AgentView` 无 persona 投影、`updatePersona` 端点已存在但响应丢 persona）——随本特性一并补上（[tasks.md](./tasks.md) 末期任务），让文档描述的「看人格卡 → 改人格」在 oryxos 里真实可操作。

**非目标（红线，逐条遵守）**: 人格市场按名引用不做；`ensurePersona`（缺 persona 用默认人格兜底）随导入链迁入 `AgentLifecycleService` 但**不接线**到 oryxos 的 `generateDraft`（生成流是 oryxos 既有分叉，与本特性正交）。

## Technical Context

**Language/Version**: Java 21（virtual thread）

**Primary Dependencies**: Spring Boot 3.x、Spring MVC、SnakeYAML（`AgentMarkdown.split`/`assembleMarkdown` 拆装 frontmatter、导入器 BLOCK dump）、Picocli（`AgentCommand` 重命令）、Spring AI Alibaba（仅协议转换 + `@Tool` schema 生成，禁用自动执行）

**Storage**: 无新增表——persona 是 `AGENT.md` frontmatter 结构化段（文件系统真相源），导入产物同为 `.oryxos/agents/<slug>/`，自定义人格是 `.oryxos/personas/<key>.md` 扁平文件；不触碰 `hibernate.ddl-auto` / `schema.sql`

**Testing**: JUnit 5 + `@TempDir` + 内存字符串（persona/parser/importer/人格库纯文件与内存可测干净）；Web 切片 standalone MockMvc（`AgentApiControllerTest` + `PersonaApiControllerTest`）；`validateAgent` dry-run 走真实 `AgentLoader`（不 mock parse）；全单测无集成冒烟

**Target Platform**: JVM 21 服务端（单二进制）

**Project Type**: Maven 多模块——本特性新增 **`oryxos-persona`** 模块承载人格库机制（理由见「模块拆分」），persona 结构化与导入器落既有 `oryxos-core`，REST 落 `oryxos-web`，装配/CLI 落 `oryxos-cli` + `oryxos-boot` 显式聚合

**Performance Goals**: 无硬指标（persona 注入是字符串拼接量级 KB；人格列表是内存 12 个 + 扫 `personas/*.md` 文件名；dry-run parse 单文件 KB 级；`ContextLoader` 每轮重读一次 frontmatter）

**Constraints**: 无新增第三方依赖；避开 P3C/ASM 解析不了的 Java 18+ 语法形态；凭证走环境变量；`PersonaStore` 路径经 `RealPathBoundary` 约束在工作区内、key 只允许 `[A-Za-z0-9_-]`

**Scale/Scope**: 整链（persona 7 字段 + 导入 + 人格库）；「先别做」——按名引用的人格市场、批量导入专家仓库全部文件、URL 源、与源库双向同步、运行时人格切换/多人格并存、自动学习进化、USER.md/SOUL.md 联动

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本节如何满足 |
|---|---|
| I. 自实现 ReAct 循环 | persona 在 `ContextLoader` 注入 system prompt（机制层），`ReActLoop`/`PromptBuilder` 一行不改 ✓ |
| II. Spring AI 仅协议转换 + schema | 本节无新增 Spring AI 调用点，不触碰自动 tool 执行 ✓ |
| III. Provider 显式映射 | 导入 provider 用 `AgentLifecycleService.defaultProvider()`（既有显式映射的默认值）兜底；预览展示的 provider/model 来自 `agentLoader.parse` 解析结果，不扫描 Bean 类型 ✓ |
| IV. 目录即 Agent + 软连接 | 导入产物进 `.oryxos/agents/<slug>/`；persona 注入走既有身份/正文注入路径；自定义人格是 `personas/*.md` 文件，不落 `agents/`，从库导入仍走 saveFiles 链落 `agents/` ✓ |
| V. 审计 Day One 落库 | 本节无新 Tool/LLM 调用路径（persona/导入/人格库是纯文件与内存操作），N/A ✓ |
| VI. 安全/真实路径校验 | 导入走 `saveFiles` 真实校验链（先校验再落盘、失败回滚、name 冲突拒绝）；`PersonaStore` 路径经 `RealPathBoundary` 约束 + key 白名单防路径穿越；预览 dry-run 不 bypass 落盘校验 ✓ |
| VII. 同步执行 + 虚拟线程 | 全同步阻塞，无 Reactor/CompletableFuture ✓ |
| VIII. 无状态外置 | persona/导入产物落 `AGENT.md` frontmatter、自定义人格落 `.oryxos/personas/`（文件系统真相源），实例无状态 ✓ |
| 模块约束（宪法 v1.1.0） | 新增 `oryxos-persona` 模块（理由见下），依赖单向无环（persona→core，web/cli/boot→persona）✓ |

无违反，无需 Complexity Tracking。

## 模块拆分（新模块理由声明，宪法 v1.1.0）

人格库机制独立成 **`oryxos-persona`** 模块：`PersonaPresetCatalog`/`PersonaService`/`PersonaStore` + 12 个 classpath 预设 + 4 个测试（`PersonaPresetCatalogTest`/`PersonaPresetsGoldenTest`/`PersonaServiceTest`/`PersonaStoreTest`）及 golden 快照目录。**理由**：人格库是「人格内容资产闭环」的能力域，与 Agent 生命周期解耦——它只消费 core 的既有契约（`RealPathBoundary`/`AgentMarkdown`），零 Spring、无跨模块依赖，独立成模块让 `oryxos-core/agent` 只留 Agent 定义/生命周期/校验，边界更纯。镜像 `oryxos-memory`/`oryxos-knowledge` 先例：**机制进模块、REST 留 web、装配留 cli**。`AgentValidation`/`AgentLifecycleService.validateAgent` 留 core（是 Agent 导入校验链，不是人格库）。模块数 13→14（本特性并入前 oryxos 已有 13 模块，含 wecom/dingtalk）。

## Project Structure

### Documentation (this feature)

```text
specs/025-persona-library/
├── spec.md              # 合并的 User Stories & FR（Part A 导入 / Part B 人格库）
├── plan.md              # 本文件
├── research.md          # Phase 0：设计决策固化
├── data-model.md        # Phase 1：persona 值对象 + 导入产物 + 人格库文件模型
├── quickstart.md        # Phase 1：端到端人工验收走查
├── contracts/
│   └── persona-api.md   # Phase 1：Web + CLI 全契约
├── checklists/
│   └── requirements.md  # spec 质量自查
└── tasks.md             # Phase 2（实施清单，随分支落地）
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxos/core/
├── profile/Profile.java             # Profile.Persona 嵌套 record（七字段）；persona 组件插在 identity 之后；旧 11 参兼容构造器加 null 透传
├── profile/ProfileLoader.java       # toProfile 在 identity 后调用 toPersona（可空，缺 name/role → ProfileValidationException；sample_style→sampleStyle）
├── context/ContextLoader.java       # renderPersona + appendPersonaField：identity.prompt 之后、AGENT.md 正文之前注入固定模板人格段；persona null 不注入
├── agent/AgencyAgentsParser.java    # 纯 POJO：frontmatter 解析 + `##` 关键词分类 → ParsedExpert（displayName/description/role/traits/background/communication/keyRules/body）
├── agent/AgencyAgentsImporter.java  # 纯 POJO：ParsedExpert + defaultProvider + availableTools → AGENT.md（BLOCK dump，tools 交集、role 空兜底）
├── agent/AgentValidation.java       # dry-run 校验结果 record（Profile + error，valid() = error == null）
└── agent/AgentLifecycleService.java # PERSONA_KEY + personaMap/defaultPersona 助手；defaultProvider()；importAgent（冲突拒绝 + saveFiles）；
                                    #   validateAgent（真实 agentLoader.parse dry-run）；updatePersona（split→Map→重序列化→parse 预校验→update）；
                                    #   ensurePersona（迁入但未接线到 generateDraft，红线）

oryxos-persona/src/main/java/io/oryxos/persona/   # 新模块：人格库机制
├── PersonaPresetCatalog.java       # 12 内置人格 classpath 只读目录（label/description/emoji/sourceFile 署名）
├── PersonaService.java             # 内置+自定义合并编排，内置只读、自定义 CRUD、copy-in（PersonaEntry 统一入口）
├── PersonaStore.java               # `.oryxos/personas/` 扁平 <key>.md 读写删列，key 校验 + RealPathBoundary 约束
└── resources/personas/*.md         # 12 份内置源文件随 jar classpath（不落工作区）；golden 快照在 src/test/resources/personas-golden/

oryxos-web/src/main/java/io/oryxos/web/controller/
├── PersonaApiController.java       # GET /api/v1/personas（合并列表）、GET /{key}（详情+源全文）、POST（新建）、PUT /{key}（更新）、DELETE /{key}（物理删）
├── dto/PersonaLibraryView.java     # 列表卡片（key/label/description/emoji/sourceFile/builtin）
├── dto/PersonaDetailView.java      # 详情（卡片 + sourceContent 全文）
├── dto/CreatePersonaRequest.java   # POST 请求体（key + sourceContent）
├── dto/UpdatePersonaLibraryRequest.java # PUT 请求体（仅 sourceContent，key 走路径）
├── dto/UpdatePersonaRequest.java   # PUT /agents/{name}/persona 请求体（七字段）
├── dto/ImportAgentRequest.java     # 导入请求（sourceContent + 可选 name）
├── dto/ImportPreviewView.java      # ImportExpertView（含 boundaries/sampleStyle）+ ValidationView（valid/message/provider/model）
├── dto/AgentView.java              # 补 persona 组件 + PersonaView 嵌套 record（from() 投影，null 时不显示卡）
└── AgentApiController.java         # PUT /{name}/persona、POST /import-preview（渲染后 validateAgent dry-run）、POST /import

oryxos-cli/src/main/java/io/oryxos/cli/
├── command/AgentCommand.java       # oryxos agent import <file> 重命令（照抄 ChatCommand 重命令启动模式）
├── OryxOsRuntime.java              # @Bean personaPresetCatalog()/personaStore()/personaService(catalog, store)
└── OryxOsCli.java                  # subcommands 注册 AgentCommand

oryxos-boot/pom.xml                 # 显式聚合 oryxos-persona 依赖

oryxos-web/src/main/frontend/src/App.vue  # 左侧导航单列「人格库」页（TOP_NAV 加 personas；内置只读查看 + 自定义新建/编辑/删除）；
                                          # Agent 新建「从人格库导入」纯选择；Agent 详情「人格」卡 + personaEdit；预览区校验状态行
```

**Structure Decision**: 镜像既有「Agent 管理」分层——机制（值对象/解析/注入/导入器/校验）落 `oryxos-core`（依赖倒置，底座持有契约），人格库机制独立成 `oryxos-persona`（理由见「模块拆分」），Web DTO/端点落 `oryxos-web`，CLI 重命令 + `@Bean` 装配落 `oryxos-cli`，`oryxos-boot` 显式聚合。**不新增表**（persona 是 frontmatter 结构化段、自定义人格是文件，不是数据实体）。改造点：`Profile` record 加组件 → 全部 `new Profile(` 调用点补 `null`（生产 2 处 + 测试若干）；`AgentView` 加 persona 组件 → 构造点收敛在 record 内 `from()`（controller 两处 `AgentView.from` 调用零改动）；`AgentApiController` 注入可用工具集做导入交集。

## 关键设计裁决（详见 research.md）

| # | 裁决 | 要点 |
|---|------|------|
| D1 | persona 是结构化字段 | 七字段值对象 + 固定模板渲染，不是「再加一段 prompt」 |
| D2 | 恒定 + 缺省零改变 | 有 persona 每轮固定注入；无 persona 不注入不含锚点 |
| D3 | 导入走 saveFiles | 复用 `agentLoader.parse` 先校验再落盘、失败回滚、冲突拒绝——不新开校验路径 |
| D4 | parser/importer 纯 POJO | 零 Spring、无 IO，`@TempDir` + 内存字符串可测 |
| D5 | 映射表是唯一事实源 | 角色/个性/沟通/关键规则→persona；记忆/经验→身份背景；任务段→正文；emoji/color 丢弃 |
| D6 | 导入 tools/provider 兜底 | `DEFAULT_TOOLS=[read_file, shell, notify]` ∩ 本机可用；provider 用 defaultProvider；model 占位 |
| D7 | 人格库 copy-in | 独立模块 + 内置只读 + 自定义 CRUD；导入=照搬原文，非按名引用（红线） |
| D8 | 预览真实校验 | dry-run `AgentLoader.parse`，永远 200，valid=false 带 message，不 bypass 落盘 |
| D9 | 编辑走 split→Map→重序列化 | 不用 replaceTopLevelScalar（嵌套多行块）；parse 预校验防写坏 |
| D10 | Agent 详情人格卡补齐 | `AgentView.persona` 投影 + 前端 info tab 人格卡/personaEdit（PUT /persona 落盘即生效） |

## Complexity Tracking

无（Constitution Check 全通过，无违反需 justify）。
