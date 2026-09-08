# Implementation Plan: 容器级执行隔离（Container Sandbox）

**Branch**: `024-container-sandbox` | **Date**: 2026-09-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/024-container-sandbox/spec.md`（issue #334）

> **评审并行推进说明**：spec 的 5 处待裁决（RQ-1~5）尚在评审，本 plan 按 spec **推荐**推进，并在受裁决影响处标注 `⚖️RQ-x`（翻案调整点）。裁决翻案只动标注点，不动整体骨架。

## Summary

shell 执行后端可插拔：`ShellTools` 既有包级 `ProcessStarter` 接缝升格为公开契约（`io.oryxos.tool.sandbox`，形状不变：`Process start(List<String>)`，destroy 语义写进 Javadoc——本地=进程、docker=容器本体），local 档=现 `ProcessBuilder` 实现（零变化）；docker 档=新增 `DockerProcessStarter` 经 CLI（`docker run --rm`）执行，零新依赖。命令构造纯函数化（`DockerRunSpec`）便于单测钉死参数；超时杀容器本体（`--cidfile` + Process 包装 destroy 联动 `docker kill`）；`ORYXOS_ROOT` 读写挂 `/workspace` + 双向路径翻译（`WorkspacePathMapper`）；`tool_invocations` 加 `execution_backend`/`container_id` 两列（`AuditSchemaUpgrade` 幂等 ALTER，镜像 020 `blocked_by` 模式）；Agent 级覆写走 `AGENT.md` frontmatter 新可选段 `sandbox:`；管理台后端状态页（REST + Vue）。检查顺序锁死：Tool Policy（020）→ 白名单 → 后端（FR-007）。

## Technical Context

**Language/Version**: Java 21（虚拟线程，同步阻塞式 CLI 调用与现 ProcessBuilder 同型，宪法 VII 无违）

**Primary Dependencies**: 仅既有依赖——docker 经 CLI（ProcessBuilder 起 `docker` 进程），**零新增 Maven 依赖**（FR-002）；Vue 3（状态页）

**Storage**: SQLite——`tool_invocations` 加 `execution_backend VARCHAR(8)`、`container_id VARCHAR(64)` 两可空列（幂等 ALTER；旧行 NULL ≡ local，查询层兼容）；无新表

**Testing**: JUnit 5——`DockerRunSpec` 纯函数单测（参数顺序/路径翻译/安全默认值钉死）、`WorkspacePathMapper` 双向翻译、`CidfileProcessWrapper` destroy 联动（mock docker kill）、frontmatter 解析与生效档位收敛；docker 依赖的 SC 用「契约测试」模式（daemon 存在才跑，缺失自动 skip 并标注，镜像 015 mem0 契约测试思路）；local 档全量既有测试零修改（SC-001）

**Target Platform**: Linux server（宿主机直跑 OryxOS + 宿主 docker CLI，RQ-1 推荐形态）；容器化 OryxOS + docker 档为 advanced 文档路径

**Project Type**: Maven 多模块单体——oryxos-tool（契约+实现）、oryxos-core（审计通道扩展）、oryxos-storage（列迁移）、oryxos-web（状态 API+页面）、oryxos-cli（装配）；**不新建模块**（roadmap 的 `oryxos-sandbox` 独立留待 SSH 档出现时再议）

**Performance Goals**: local 档零开销（SC-001）；docker 档单次执行增加 ~300-800ms（CLI+容器启动，安全收益远大于此，文档明示）；daemon 探测按需触发（启动校验/状态页/失败分类三处），**零常驻心跳线程**（宪法 VII）

**Constraints**: 检查顺序 Policy → 白名单 → 后端不可变（FR-007）；docker 档 fail loud 不静默回落 local（FR-011，档位是承诺）；镜像必须显式配置（FR-005）

**Scale/Scope**: ~10 新文件 + ~8 既有文件小改；26 任务 6 阶段（见 tasks.md）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 评估 | 结论 |
|------|------|------|
| 一 · 自实现 ReAct Loop | ReActLoop / ToolExecutor 零改动——后端是 ShellTools 内部执行细节，工具签名与语义不变 | ✅ |
| 二 · Spring AI 只用两件事 | 不涉及 | ✅ |
| 三 · Provider 必须显式映射 | 不涉及 | ✅ |
| 四 · 一个目录 = 一个 Agent；Skill 软连接绑定并渐进披露 | frontmatter 新增**可选** `sandbox:` 段——作者层执行环境声明，与治理层（020）分离同构；不配即继承全局，目录模型与 skills 软连接规则不动 | ✅ |
| 五 · 审计表 Day One 写入 | backend 标识 + 容器 ID 随每次调用落 `tool_invocations`（FR-008），容器化执行不产生审计盲区 | ✅ |
| 六 · 不使用 Java SecurityManager；软连接必须校验真实路径 | 容器是内核边界而非 SecurityManager；白名单（含真实路径校验）enforce 前置不变（FR-007）；默认参数安全优先（none/512m/read-only，RQ-5） | ✅ |
| 七 · 同步执行模型 | docker CLI 为同步阻塞 Process 调用，与现状同型；无常驻探测线程 | ✅ |
| 八 · Tool 模块三合一 | 契约+实现归 oryxos-tool 既有 sandbox 包，不新建模块、无循环依赖 | ✅ |
| 补充 · 状态外置 / 手工 schema（非编号原则，020 先例口径） | ALTER 走 `AuditSchemaUpgrade` 幂等模式（`blocked_by` 先例），不依赖 Hibernate 迁移 | ✅ |

**Phase 1 设计后复评**: 无新增违背项，全部通过。

## Project Structure

### Documentation (this feature)

```text
specs/024-container-sandbox/
├── spec.md              # 特性规格（15 FR / 8 SC / 3 US / 5 RQ）
├── plan.md              # 本文件
└── tasks.md             # 26 任务 6 阶段
```

### Source Code (repository root)

```text
oryxos-tool/src/main/java/io/oryxos/tool/
├── builtin/ShellTools.java              # 修改：删除包级 ProcessStarter，改引 sandbox 公开契约；其余零改动
├── sandbox/ProcessStarter.java          # 新增：公开契约（自 ShellTools 升格；Javadoc 写死 destroy 语义）
├── sandbox/LocalProcessStarter.java     # 新增：现 ProcessBuilder 默认实现（逐字节搬家）
├── sandbox/DockerRunSpec.java           # 新增：纯函数——配置+命令 → docker run 参数序列（可单测钉死）
├── sandbox/DockerProcessStarter.java    # 新增：CLI 执行 + CidfileProcessWrapper（destroy→docker kill）
├── sandbox/WorkspacePathMapper.java     # 新增：ORYXOS_ROOT ↔ /workspace 双向翻译
└── sandbox/ExecutionBackendProperties.java # 新增：配置属性（镜像/限额/网络/user），镜像 ShellSandboxProperties 风格

oryxos-core/src/main/java/io/oryxos/core/
├── provider/ToolInvocationAuditor.java  # 修改：record 加 backend/containerId 重载（旧签名委托，镜像 020 blockedBy 模式）
└── agent/AgentProfile.java（或其解析处）# 修改：frontmatter 可选 sandbox 段解析（Phase 4）

oryxos-storage/src/main/java/io/oryxos/storage/
├── AuditSchemaUpgrade.java              # 修改：幂等 ALTER 两列（镜像 blocked_by 模式）
├── ToolInvocation.java / JpaToolInvocationAuditor.java  # 修改：落列
└── AuditSchemaUpgradeTest.java          # 修改：两列升级用例

oryxos-cli/src/main/java/io/oryxos/cli/
└── OryxOsRuntime.java                   # 修改：装配（backend 选择 + ShellTools 注入 starter + 启动校验注册）

oryxos-web/src/main/java/io/oryxos/web/
└── controller/SandboxBackendController.java + Vue 状态页  # 新增：只读状态 API + 页面（Phase 5）
```

## Key Design Decisions

| # | 决策 | 理由 | 翻案敏感度 |
| --- | --- | --- | --- |
| D1 | 保留 `ProcessStarter` 名与形状，仅升公开 + Javadoc 契约化（destroy MUST 终止真实执行本体） | 改名/改形状（如换成 Result 记录）是无谓 churn；形状已兼容 docker/ssh 两档 | 低 |
| D2 | docker 命令构造 = `DockerRunSpec` 纯函数（配置+argv → 完整 docker argv） | 参数顺序/翻译/默认值可单测钉死（SC-002/003/005 的第一道防线），装配期可校验 | 低 |
| D3 | 超时杀容器：`--cidfile` + `CidfileProcessWrapper`（流的委托 + destroy 联动 `docker kill`；cidfile 未及写入则仅杀 CLI） | FR-006 的机制核心；纯本地可 mock 单测 | 低 |
| D4 | 审计两列 nullable，旧行 NULL≡local（查询层兼容）；新调用恒写值 | 存量库零破坏；避免全表回填 | 低 |
| D5 | daemon 探测按需（启动校验/状态页/失败分类），零常驻线程 | 宪法 VII；探测频率无 SLA 需求 | 低 |
| D6 | 路径翻译 = ORYXOS_ROOT 前缀 ↔ `/workspace` 字符串映射（不做 realpath 跟随）；审计记宿主原始路径 | 容器内路径无宿主 realpath 语义；前缀映射可预测可单测 | ⚖️RQ-4（若裁决改挂载点/只读，仅动 Mapper 与挂载参数） |
| D7 | Phase 1 不建 `oryxos-sandbox` 模块，契约与实现归 oryxos-tool 既有 sandbox 包。**与立项输入（issue #334 提出独立模块、roadmap 方向 F「视情况」）的显式偏差**：尊重原则八（Tool 三合一、不轻建模块），零实现档出现前不预拆。**跨模块化路径预留**：SSH 档出现时按依赖倒置将契约迁 oryxos-core（届时三消费方以上，构成真实跨模块诉求） | 零新依赖零新模块；模块拆分有明确的触发条件而非拍脑袋 | ⚖️RQ-2/RQ-3（若裁决扩大范围，模块化议题重开） |
| D8 | 生效档位 = frontmatter `sandbox.backend` > 全局配置；非法值 WARN + 回落全局（不阻断其它 Agent） | 与 020 例外登记同构「配置即责任」；fail-soft 只用于声明层，运行层 fail-loud（FR-011） | ⚖️RQ-5（限额/网络默认值调整只动 Properties 默认值与 D2 构造） |

## Implementation Phases（与 tasks.md 对齐）

| Phase | 内容 | 出口（Checkpoint） |
| --- | --- | --- |
| 1 Setup | 审计列迁移 + 配置属性类 | 升级用例绿；`oryxos.sandbox.execution.*` 可配 |
| 2 Foundational | ProcessStarter 升格 + Local 实现 + DockerRunSpec + PathMapper + CidfileProcessWrapper（全纯函数/可 mock） | 单测全绿；local 档既有测试零修改（SC-001 锚点） |
| 3 US1 MVP | DockerProcessStarter 装配 + 启动校验 + 审计贯通 + fail-loud + SC-002~006 契约测试 | docker 档最小闭环可用 |
| 4 US2 | frontmatter sandbox 段 + 生效档位收敛 + 按 Agent 限额 + EC-4 | SC-007/SC-008 绿 |
| 5 US3 | 状态 API + Vue 状态页 | SC 佐证面齐 |
| 6 收尾 | website 双语 / CLAUDE.md / advanced 部署文档（socket 三候选） + 验收走查 | 全量 `mvn verify` + SC-001~008 落卷 |

## Risks

1. **destroy 联动的竞态**（容器极快退出时 cidfile 未写）——D3 的 fallback 路径必须有测试（容器已退则 kill 报错按成功处理）。
2. **Windows/macOS Docker Desktop 的挂载翻译差异**——契约测试仅承诺 Linux；桌面环境文档标注为开发用途（EC-6）。
3. **卷属主与非 root --user 的写权限**——EC-3：错误信息给 chmod/chown 提示，不做自动修属主（越权风险）。
