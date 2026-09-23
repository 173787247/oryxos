# 024 容器级执行隔离 · 验收走查（quickstart，T021）

> 真机环境：Windows + Docker Desktop 28.4.0 · alpine:3.20 · 2026-09-15
> 配置基线：`oryxos.sandbox.execution.backend: docker` + `image: alpine:3.20`；验收 Agent 两个——
> `agent-local`（frontmatter `sandbox: {backend: local}`）与 `agent-docker`（无覆写，继承全局）

## V1 · 启动校验三态（T011 / FR-005）

| 步骤 | 操作 | 预期 | 实测 |
| --- | --- | --- | --- |
| V1.1 | daemon 停止时启动 serve | 抛 `IllegalStateException` 阻断启动，消息含排障信息（pipe 错误原文） | ✅ `docker daemon 不可达（docker info 失败）… open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified` |
| V1.2 | daemon 就绪时启动 serve | 日志 `docker 执行后端就绪: image=… network=… memory=… cpus=…` | ✅ `image=alpine:3.20 network=none memory=512m cpus=1.0` |
| V1.3 | local 档（默认/未配）启动 | 零检查零开销，无任何 docker 探测日志 | ✅ |

## V2 · 状态页 API（US3 / FR-012）

`GET /api/v1/sandbox/execution/status`：

- docker 档：`backend=docker`、`reachable=true` + CLI 版本、镜像 digest 实时探测（sha256 前缀）、限额四项、覆写一览**只列声明段**（`agent-local`，`agent-docker` 正确缺席）——✅ 全项实测
- local 档：`backend=local`、digest=null（不探测镜像）、daemon 状态照常呈现（环境信息）——✅
- 管理台 SPA：`/admin/` 200 且构建产物含「执行后端」页（`exec-backend` key 与文案均在 bundle）——✅

## V3 · SC-008 双 Agent 双执行环境

同一条指令 `cat /etc/os-release`：

| Agent | 生效档 | 回复 | 审计 |
| --- | --- | --- | --- |
| agent-docker | docker（继承） | `ID=alpine`（Alpine Linux v3.20） | backend=docker + **containerId=64 位真实 ID** |
| agent-local | local（覆写） | `cat: /etc/os-release: No such file or directory`（Windows 宿主无此路径） | backend=local |

✅ 双环境路由正确，以审计为 ground truth。

## V4 · SC-007 审计筛选

- `?backend=docker` 只返回容器执行记录；`?backend=local` 只返回本地记录 —— ✅
- `ToolInvocationView` 暴露 `executionBackend`/`containerId` 两列 —— ✅

## V5 · 容器治理

- 正常退出后 `docker ps -a` 零 alpine 残留（`--rm`）—— ✅
- 超时杀容器无泄漏：契约测试 SC-004（`DockerProcessStarterIT`，6/6 真机通过）—— ✅

## V6 · SC-001 local 档零回归

全局切回 local：agent-docker（继承）shell 在宿主执行（`echo` 输出正常回传）、审计 backend=local；未配 backend 的部署行为与 024 之前一致（Phase 2 起既有测试零修改 + 真机端到端）—— ✅

## V7 · 过程中发现并修复的缺陷

**containerId 竞态（真机验收发现）**：初版在 `onExit()` 回调里直接删除 cidfile，与审计的惰性读取竞态——容器一退出文件即删，审计读到 null（单测层无法暴露：单元测试不走到"执行完成后审计"的时序）。修复：退出回调**先捕获容器 ID 到 AtomicReference 再清理**，审计 supplier 优先读缓存。复验：docker 档审计记录 `containerId=a688d916…`（修复前旧记录为 null，修复后新记录实录）—— ✅
