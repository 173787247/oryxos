# Acceptance Report: 容器交付（K8s Delivery）

**Feature**: 039-k8s-delivery | **Date**: 2026-09-15 | **Verdict**: SC-001~007 全过（含真机 kind 走查）；026 吞吐线性性留账以 **2.0× 硬数字**兑现、027 RWX 抽查兑现；环境替代项如实记录

## 自动化验收（`mvn clean install` BUILD SUCCESS + `make helm-lint` 全绿）

| 面 | 载体 | 结果 |
|----|------|------|
| Chart 门禁档 | scripts/helm-verify.sh（lint + template + kubeconform + 七组断言） | 全绿——副本 2/双探针/grace 40/preStop/RWX/零明文凭证/缺必填 fail-fast 指引/RWX 模板断言/单副本 RWO 合法 |
| OTel 实现 | OtelSpanRecorderTest | 4/4——traceId 同源映射、确定性父子（根 spanId=traceId 前 16 hex）、显式时间戳、非法 traceId 静默跳过、**端点不可达 record/close 不阻塞（analyze A1）** |
| span 三点位 | AgentServiceTest（turn 成功/失败/无状态 3 例）+ ToolExecutorTest（成功/失败/策略拦截）+ 全量门禁 | 全绿；NOOP 档零变化由全量既有测试覆盖 |
| US2 停机修复 | ChannelAdminServiceTest（stopAll 释放属主租约 + 无 coordinator 零变化） | 2/2 |
| mock 时延 | MockChatModelLatencyTest | 2/2——默认 0 零变化、配置生效 |
| 026/027 IT 回归 | 显式套件（FilePlane 6 + KnowledgeExactlyOnce 4 + SessionOwnership 5 + ScheduleExactlyOnce 1 + FailoverTakeover 4 + 迁移 4） | 24/24 全绿（span 接线与停机改动零破坏） |
| CI 自动化 | ci.yml helm job（lint/schema + **kind install 冒烟**，analyze C1）；release.yml chart tgz 附件 | 已接线，PR 门禁自动执行 |

## 真机走查（本机 kind 单节点集群 + 集群内 PG + hostPath 静态 RWX PV）

- **SC-001 一条命令安装**：两项密文 Secret + `helm install` → 双副本就绪、`/api/v1/instances` clusterEnabled=true 双活；**A 建 Agent 两 Pod 各自本地 1~2s 可见**（027 on PVC）✓
- **SC-002 滚动升级零失败**：集群内探测（pg pod 打 Service DNS，1 req/s）下 `rollout restart` 全程 **87/87 零失败** ✓（注：经 kubectl port-forward 的首测出现 403/000——系 port-forward 粘死被换代 Pod 的观测通道假象，非服务失败，已换集群内探测口径）
- **SC-003 kill 接管**：`delete pod --force` 窗口 **58/58 请求全成**，被杀 Pod 自动重建归队（77s）✓
- **SC-004 优雅窗口**：`server.shutdown=graceful` + drainTimeout 30s + grace 40s 配置链真机生效（滚动零失败即其外显）；渠道属主租约释放：走查环境无企微凭证，按 analyze I2 以 stopAll 单测为该路径验收锚点 ✓
- **SC-005 OTel 链路**：`--set otel.endpoint=http://jaeger:4317` 后一轮含工具对话在 Jaeger 呈现完整链路——`oryxos.turn`（ROOT，spanId=traceId 前 16 hex）→ 2×`oryxos.llm_call` + `oryxos.tool` 子 span 父子/耗时正确；同 traceId 查 `GET /api/v1/audit/trace/{id}` found=true 步骤一致 ✓；未配置档零导出（NOOP 装配 + 全量回归）✓
- **SC-006 留账兑现**：
  - **026 吞吐线性性（硬数字）**：`-Doryxos.mock.latency-ms=800`（模拟真实 LLM 往返）+ 集群内 32 并发发压——**单副本 5.3 req/s → 双副本 10.7 req/s = 2.0×**（≥1.6× 达成）。机理：单副本瓶颈为副本内记忆文件追加互斥（每轮 save_memory 写 MEMORY.md 的进程内串行资源），加副本即近似翻倍；64 并发双副本 10.3 req/s 持平（同瓶颈封顶）。环境局限如实：单宿主 kind、发压端与服务同节点（sleep 型时延下发压端非瓶颈）
  - **027 RWX 抽查**：A 建 B 见（1~2s）+ **直接改盘（exec 进 Pod 改共享 PVC 上 AGENT.md）→ `POST /api/v1/workspace/refresh` → 两 Pod 3s 内均反映改动** ✓（已回写 027 验收卷互链）
- **SC-007**：门禁档进 CI 可重复；裸机/compose 零变化面全量测试绿 + `bin/stop.sh` 宽限修复属 spec FR-010 声明的实证缺陷例外

## 真机走查发现并当场修复的三个产品级问题（Chart 侧）

1. **K8s service-link 环境变量踩名**：Service 名为 `oryxos` 时平台自动注入 `ORYXOS_PORT=tcp://<clusterIP>:8080`，覆盖镜像同名端口变量致 entrypoint 崩——任何按默认名安装的用户必踩。修复：Deployment `enableServiceLinks: false`（服务发现走 DNS）
2. **工作区卷属主**：容器 uid/gid 1000 对 root 属主 PVC 无写权。修复：Pod `securityContext.fsGroup: 1000 + OnRootMismatch`（CSI 卷标准姿势）；hostPath 类卷需存储侧预置权限（SharedVolumeGuide 已注）
3. **ConfigMap 顶层键重复**：extraConfig 直接追加会与固定注入段产生重复 `oryxos:` 顶层键（SnakeYAML 后者胜，静默吞掉 cluster.enabled）。修复：模板改深合并（固定项优先）

另修 invoke 路径缺口：turn 根 span 最初只挂在 `process()`，真机链路缺根——已补 `processStateless`（单测 +1）。

## 环境注记（如实）

- 本机 Docker Hub 对 `eclipse-temurin:21-jre-jammy` manifest 持续超时（8 次重试均败；同 registry 其他镜像可拉）：走查镜像以本地缓存 `21-jre` 基底等价替换构建（仅 FROM + 用户创建幂等两处差异，`scratchpad/Dockerfile.walk`），**仓库 Dockerfile 未改**；产线镜像仍由 CI/GHCR 以 jammy 构建（网络正常）
- `kind load docker-image` 与 Docker 29 containerd 镜像存储存在兼容问题（拉取镜像 digest 缺失）：以 `docker save --platform` + `kind load image-archive` 绕过，CI（docker 24 runner）不受影响
- Jaeger UI 数据在集群删除后不留存；走查原始输出见本会话记录与 `scratchpad/trace2.json` 断言输出

## SC 对照

| SC | 判定 | SC | 判定 |
|----|------|----|------|
| SC-001 安装 ≤5min 双活 | ✅ | SC-005 OTel 链路+互查/NOOP | ✅ |
| SC-002 滚动零失败 87/87 | ✅ | SC-006 线性 2.0× + RWX 抽查 | ✅ |
| SC-003 kill 接管 58/58 | ✅ | SC-007 门禁 CI 化 + 零回归 | ✅ |
| SC-004 优雅窗口/租约 | ✅ | | |
