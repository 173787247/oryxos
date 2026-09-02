# Tasks: 容器级执行隔离（Container Sandbox）

**Input**: Design documents from `/specs/023-container-sandbox/`（spec.md + plan.md）

**Prerequisites**: spec.md（15 FR / 8 SC / 3 US / 5 RQ）、plan.md（D1~D8 设计决策）

**Tests**: 包含测试任务——质量门禁要求核心逻辑单测覆盖；「local 零回归」（SC-001）与「超时杀容器」（SC-004/FR-006）必须测试钉死；docker 依赖的验收用契约测试模式（daemon 缺失自动 skip 并标注，镜像 015 mem0 契约测试思路）。

**Organization**: 契约与纯函数层一次成型在 Foundational（Phase 2），三个故事承载消费面——US1（docker 档最小闭环）为 MVP，US2（按 Agent 覆写）、US3（状态页）依次叠加。裁决翻案敏感任务已标 `⚖️RQ-x`。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1/US2/US3），基础设施任务标 [F]

## Path Conventions

Maven 多模块单体——oryxos-tool（契约+实现）/ oryxos-core（审计通道）/ oryxos-storage（列迁移）/ oryxos-cli（装配）/ oryxos-web（状态页）。

---

## Phase 1: Setup

**Purpose**: 存储与配置地基

- [ ] T001 [P] [F] 修改 `oryxos-storage/.../AuditSchemaUpgrade.java`：幂等 ALTER 为 `tool_invocations` 加 `execution_backend VARCHAR(8)`、`container_id VARCHAR(64)` 两可空列（PRAGMA table_info 检查模式，镜像 020 `blocked_by` 先例）；`AuditSchemaUpgradeTest` 追加两列升级用例
- [ ] T002 [P] [F] 新建 `oryxos-tool/.../sandbox/DockerSandboxProperties.java`：`image/memory/cpus/network/user`（默认 `512m`/`1.0`/`none`/`65534:65534`）+ 全局 `oryxos.sandbox.backend`（`local|docker`，默认 local）；镜像 ShellSandboxProperties 的属性类风格；配套绑定测试（默认值钉死，SC-001 锚点）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 契约升格 + 四个纯函数/可 mock 组件——所有消费面的公共依赖

**⚠️ CRITICAL**: 本阶段完成前不得开始任何用户故事

- [ ] T003 [F] 新建 `oryxos-tool/.../sandbox/ProcessStarter.java` 公开接口（自 ShellTools 包级升格，形状不变 `Process start(List<String>)`）；**Javadoc 写死契约**：`destroy()` MUST 终止真实执行本体（本地=进程 / docker=容器），超时语义依赖此条（FR-006 的契约化）；修改 `ShellTools.java` 删除包级定义改引公开版
- [ ] T004 [P] [F] 新建 `oryxos-tool/.../sandbox/LocalProcessStarter.java`：现 ShellTools 内 ProcessBuilder 默认实现逐字节搬家（含既有 SuppressFBWarnings 注释跟随）；ShellTools 构造注入点不变——**搬运不改行为**（SC-001 的第一道防线）
- [ ] T005 [P] [F] 新建 `oryxos-tool/.../sandbox/WorkspacePathMapper.java`（⚖️RQ-4）：`toContainer(hostPath)` / `toHost(containerPath)` / `isWorkspacePath(host)`——ORYXOS_ROOT 前缀 ↔ `/workspace` 纯字符串映射，不做 realpath；单测：双向翻译、非工作区路径直通、相对路径直通、尾分隔符归一
- [ ] T006 [F] 新建 `oryxos-tool/.../sandbox/DockerRunSpec.java`（⚖️RQ-5）：纯函数 `List<String> build(DockerSandboxProperties props, Path workspaceRoot, List<String> command)` → 完整 docker argv（`run --rm --cidfile <tmp> -v <root>:/workspace --network ? --memory ? --cpus ? --read-only --tmpfs /tmp --user ? <image> <cmd…>`，命令内工作区路径经 T05 翻译）；单测钉死：参数顺序、默认安全参数全在场（SC-005 的防线）、入参翻译、自定义网络/限额覆写
- [ ] T007 [F] 新建 `oryxos-tool/.../sandbox/CidfileProcessWrapper.java`：包装 docker CLI Process——流委托；`destroy()` 读 cidfile → `docker kill <id>`（容器已退/无 cidfile 则仅杀 CLI，kill 报错按成功处理，plan 风险 1）；单测 mock docker kill 断言联动与 fallback 两分支
- [ ] T008 [P] [F] 修改 `oryxos-core/.../ToolInvocationAuditor.java`：`record` 加 `executionBackend`/`containerId` 重载（旧签名委托 `local`/null，镜像 020 blockedBy 模式）；`ToolInvocation` + `JpaToolInvocationAuditor` 落列（依赖 T001）

**Checkpoint**: 全部单测绿 + local 档既有全量测试零修改通过（SC-001 在实现层的第一道验收）

---

## Phase 3: User Story 1 - docker 档最小闭环 (Priority: P1) 🎯 MVP

**Goal**: 全局切 docker 档，shell 在短命容器执行，白名单/Policy 照常前置，审计带后端标识，docker 故障 fail loud

- [ ] T009 [US1] 新建 `oryxos-tool/.../sandbox/DockerProcessStarter.java`：组合 T006+T007——build argv → 起 docker CLI → CidfileProcessWrapper 包装返回；docker 失败分类（CLI 缺失 / daemon 不可达 / 镜像缺失）转结构化错误信息（FR-011 口径：含排障提示，不回落 local）
- [ ] T010 [US1] 修改 `oryxos-cli/.../OryxOsRuntime.java` 装配：按 `oryxos.sandbox.backend` 选择 starter（local→T004 / docker→T009）注入 ShellTools（现 `new ShellTools(sandbox)` 单点改）；**检查顺序不变**：ShellTools 内 sandbox.enforce 仍先于 starter（FR-007 无需改动即成立，测试钉死）
- [ ] T011 [US1] 新建 `oryxos-web/.../security/DockerBackendStartupCheck.java`（镜像 020 ToolPolicyStartupCheck：ApplicationRunner + @ConditionalOnWebApplication）：backend=docker 时校验 CLI 存在 / `docker info` 可达 / 镜像存在（必要时 `docker pull` 预拉取，FR-005），任一失败 fail loud；配套单测三分支
- [ ] T012 [US1] ShellTools 审计贯通（依赖 T008/T009）：shell 调用记录 backend 标识与容器 ID（从 wrapper 取 cidfile 内容）；local 档记录 `local`
- [ ] T013 [US1] 契约测试 `DockerProcessStarterIT`（daemon 存在才跑，缺失 skip+标注）：SC-002（容器发行版≠宿主 + `--rm` 零残留）、SC-003（容器内写 /workspace 宿主可见）、SC-004（sleep 超时→无泄漏容器）、SC-005（network=none 下外网失败）、SC-006（停 daemon→结构化错误+进程存活+恢复后自愈）

**Checkpoint**: MVP 演示走查（spec US1 Independent Test 全步）可录

---

## Phase 4: User Story 2 - 按 Agent 覆写与限额 (Priority: P2)

**Goal**: frontmatter `sandbox:` 段覆写全局档位与限额；非法值告警回落

- [ ] T014 [P] [US2] frontmatter 解析：AgentProfile（或其解析处）加可选 `sandbox.backend` + `sandbox.docker.{memory,cpus}`；非法档位值→加载 WARN + 回落全局（EC-4 口径：不静默、不阻断其它 Agent）；单测覆盖合法/非法/缺省三态
- [ ] T015 [US2] 生效配置收敛（D8）：`EffectiveSandboxConfig resolve(global, agent)`——frontmatter 覆写优先、未配继承全局；DockerRunSpec 接收生效配置（T010 装配点改为按 Agent 解析后传入）；单测钉死收敛优先级
- [ ] T016 [US2] 契约测试补充 SC-007（审计按 backend 筛选含容器 ID）、SC-008（两 Agent 同命令宿主/容器两种发行版）

**Checkpoint**: US2 独立验收全步绿

---

## Phase 5: User Story 3 - 管理台后端状态页 (Priority: P3)

**Goal**: 档位/daemon 探测/镜像/限额/覆写一览，只读

- [ ] T017 [P] [US3] 新建 `oryxos-web/.../controller/SandboxBackendController.java`：GET 状态（档位、daemon 可达性+CLI 版本按需探测 D5、镜像 digest、默认限额、Agent 覆写一览）；REST 单测（mock 探测器）
- [ ] T018 [US3] Vue 状态页：镜像 020 策略页版式——daemon 标红态与 SC-006 错误口径一致；中英文案

**Checkpoint**: US3 验收场景两步可在管理台复现

---

## Phase 6: 文档与验收走查

**Purpose**: 文档同步 + 全量门禁 + SC 落卷（017「真机验收落卷」先例）

- [ ] T019 [P] [F] website 双语文档：新增/扩展 sandbox 页——backend 配置、路径映射语义（FR-014：file 工具与容器内写入互见）、超时行为、advanced 部署（容器化 OryxOS + socket 三候选风险表，⚖️RQ-1 文档化承诺）
- [ ] T020 [P] [F] CLAUDE.md：宪法六补充容器边界与白名单的关系表述 + 工作区结构标注 `/workspace` 映射；README feature 列表一句话
- [ ] T021 [F] 验收走查 quickstart（镜像 020 V1~V7 形式）：SC-001~008 逐步操作录证；`mvn verify` 全量门禁绿；EC-1~8 逐条过（桌面环境差异 EC-6 允许文档化豁免）
- [ ] T022 [F] 真机验收落卷：验收报告（020 acceptance-report.md 先例）——实测数据（执行延迟对比 local/docker、超时终止、恢复自愈）入卷

---

## 任务统计

26 项任务按 6 阶段组织（T001~T022 编号顺排，其中 T013/T016 为多场景契约测试合编）；US1 为 MVP 独立可交付，US2/US3 依次叠加，文档收尾贯穿。
