# Tasks: 042 高可用共享工作区

## Phase 1 — Setup
- [x] T001 建立需求、研究、计划和契约 specs/042-workspace-storage/

## Phase 2 — Foundation
- [x] T002 实现并测试插件契约、注册和 NIO 标准接口桥接 oryxos-core/src/main/java/io/oryxos/core/workspace/

## Phase 3 — US1 可插拔存储
- [x] T003 [US1] 将所选 Path 注入工作区业务与工具入口 oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java
- [x] T004 [US1] 验证替代插件实际接管 Agent/Skill/知识及工作区读写 oryxos-{core,cli,knowledge,web}/src/test/java/

## Phase 4 — US2 可靠提交与收敛
- [x] T005 [P] [US2] 增加漏通知对账、存储故障快照保留和失败重试测试 oryxos-core/src/main/java/io/oryxos/core/cluster/WorkspaceVersionPoller.java
- [x] T006 [US2] 补齐原子覆盖和下载中断测试 oryxos-core/src/main/java/io/oryxos/core/io/AtomicFiles.java 与 oryxos-tool/
- [x] T007 [US2] 增加管理提交预约、版本冲突及维护恢复 oryxos-core/src/main/java/io/oryxos/core/workspace/

## Phase 5 — US3 共享身份与健康
- [x] T008 [US3] 校验共享身份并验证丢挂载拒绝访问 oryxos-core/src/main/java/io/oryxos/core/workspace/
- [x] T009 [US3] 装配共享存储 readiness 和配置 oryxos-boot/

## Phase 6 — US4 输出与运维
- [x] T010 [US4] 隔离 agent/run 输出与原生执行发布边界 oryxos-core/src/main/java/io/oryxos/core/agent/ 与 oryxos-tool/
- [x] T011 [P] [US4] existingClaim 配置及迁移/备份恢复/跨主机验收文档 charts/oryxos/ 与 docs/SharedVolumeGuide.md

## Phase 7 — Verification
- [x] T012 执行一致性分析、模块回归、质量检查和独立审查 specs/042-workspace-storage/acceptance.md
- [ ] T013 在真实双节点共享卷验证故障切换并记录证据 specs/042-workspace-storage/acceptance.md

## Dependencies / parallel execution
T001 → T002 → T003/T008 → T004/T009。T005 与 T011 可独立实现。T006/T007/T010 需依赖存储契约并顺序协调共有入口文件。T012 依赖实现，T013 依赖外部真实共享环境。

## Strategy
先保持本地兼容并证明插件真实分派，再共享拒绝错误挂载及故障可靠性，最后多节点验收；不把文档演练命令当实际通过。


## 集成验收细项

- T003/T004：替代插件必须覆盖 Agent/Skill 读写、知识绑定和工作区入口；根路径不同于配置值时，工具白名单使用所选根及显式执行视图。绑定的 expectedTarget 必须属于同一文件系统。
- T007：Agent 详情/文件、Skill 详情/列表的编辑视图读取规范文件；Agent、Skill、Persona、知识上传/删除及绑定编辑保存读取时的代次。覆盖滞后注册表、新代次旧正文、未知知识库导致的错误请求、后处理 4xx 与 5xx 的状态表回归。
- T006/T007：管理读取和下载拒绝内部原子写临时文件，包含别名访问；不能仅在目录树中隐藏。
- T012：最终结果以 acceptance.md 的实际命令为准；未执行的门禁不得标为通过，合并前须满足项目全部门禁。
- T013：记录真实节点和共享卷参数；本机测试不能替代，外部环境未提供时保持未完成。

## 用户要求的交付补齐（2026-09-18）

- [x] T014 依赖告警逐项核实、可修补升级、精确条件排除及 CI 失效检测；当前产物扫描无未处理条目。
- [x] T015 隔离干净构建、全部 CI IT、额外知识/重启/真实在线 IT、阻塞探针及真实 MCP 传输测试。
- [x] T016 Docker 与 Helm existingClaim/shared-posix 实际安装、浏览器编辑冲突/知识上传、身份故障与本机备份恢复演练。
- [ ] T017 提交可审核 PR 并完成远程 CI 检查；真实跨主机 T013 未完成时保留 Draft 与明确阻塞说明。

- [x] T018 实测 NFS hard-mount 阻塞时直连 Pod 的管理写入，修复过期存储探针的准入缺口，验证 5 秒内服务端拒绝、恢复后无延迟写入及正常写入恢复；保留故障发现窗口与在途 I/O 边界。
