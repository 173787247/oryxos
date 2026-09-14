# Quickstart: 容器交付（039-k8s-delivery）验证指南

**前提**: 039 实现完成；`make build` 产出 boot jar；helm/kubeconform 已装（用户目录，见 V0）。真机档另需 docker + kind（环境阶梯见 V0）。

## V0 环境阶梯（WSL2 实证：初始无 docker/kind/helm）

1. **门禁档工具（无需 docker）**：`helm`、`kubeconform` 二进制 curl 安装至 `~/bin`（脚本 `scripts/install-k8s-tools.sh` 提供）。
2. **真机档 docker 获取（按序尝试）**：Docker Desktop 设置 → Resources → WSL integration 开启本 distro（推荐，一次性用户操作）→ 或 `sudo apt install docker-ce` → 或 k3s 裸跑替代 kind。
3. kind/kubectl 二进制同样装 `~/bin`。环境不可得时：完成门禁档 + 如实记录待补真机项（沿 026/027 口径）。

## V1 门禁自动化（无集群，全部可重复）

```bash
make helm-lint         # helm lint + template + kubeconform + scripts/helm-verify.sh 断言
mvn -q clean install   # 全量质量门禁 + SpanRecorder/停机修复单测
```

断言要点：lint 零 error；渲染产物副本 2/双探针/grace 40/preStop/RWX/零明文凭证；缺必填 values 渲染失败带指引；NOOP 档零导出（InMemory exporter 单测）；`ChannelAdminService.stopAll` 释放属主租约单测；全量既有测试零回归（裸机/compose 零变化面）。

## V2 真机安装冒烟（US1，kind）

```bash
kind create cluster --config ci/kind-two-node.yaml   # 双节点便于分布副本
# 集群内起 PG（bitnami chart 或裸 pod）+ NFS provisioner（RWX）
kubectl create secret generic oryxos-db --from-literal=SPRING_DATASOURCE_URL=... 等三键
kubectl create secret generic oryxos-master-key --from-literal=ORYXOS_MASTER_KEY=$(openssl rand -base64 32)
kind load docker-image oryxos:<version>              # 本地镜像入集群
helm install oryxos charts/oryxos \
  --set database.existingSecret=oryxos-db \
  --set masterKey.existingSecret=oryxos-master-key \
  --set image.repository=oryxos --set image.tag=<version>
kubectl rollout status deploy/oryxos   # ≤5min 双副本就绪（SC-001）
```

验证：`/api/v1/instances` 双活；经任一 Pod 建 Agent 另一 Pod ≤3s 可见（027 on RWX，兼 US4 抽查）；`replicaCount=3` upgrade 后三活；漏配 Secret 时 install 报错指出缺失键。

## V3 滚动升级与故障（US2）

```bash
# 持续压测（独立终端，1 req/s 起）：scripts/rolling-probe.sh 记录每请求结果
helm upgrade oryxos charts/oryxos --set image.tag=<new>   # 逐副本替换
# 断言：探测输出零非预期失败（SC-002）；升级后 instances 为新代
kubectl delete pod <one> --grace-period=0 --force          # 模拟节点故障
# 断言：后续请求 100% 成功、Pod 重建归队（SC-003）；正常滚动时 preStop 日志含渠道租约释放
```

在途轮次抽查：升级触发时保持一条处理中会话（mock-latency 拉长），断言优雅窗口内完成或标失败、后续消息不被悬空租约阻塞超过等待窗口（SC-004）。

## V4 OTel 链路（US3）

```bash
# kind 内起 jaeger all-in-one（含 OTLP gRPC 4317）
helm upgrade oryxos charts/oryxos --set otel.endpoint=http://jaeger:4317
# 跑一轮含工具调用的对话 → Jaeger UI 查 traceId（响应体/审计接口取）
```

断言：链路 turn 根 + llm/tool 子 span、父子与耗时正确；同 traceId 查 `GET /api/v1/audit/trace/{id}` 与库内记录一致（SC-005）；卸掉 endpoint 后零导出尝试。

## V5 US4 留账复测

- **026 吞吐线性性**：`mock-latency-ms=800` 部署；独立发压（集群外进程，并发 ≥64）分别压 `replicaCount=1` 与 `2` 同负载，记录吞吐比（目标 ≥1.6×，环境局限如实标注）。
- **027 RWX 抽查**：V2 已含 A 建 B 见；补改盘 + `POST /api/v1/workspace/refresh` 走查；结果回写 027 acceptance-report 互链。

## V6 零回归

裸机 `bin/start.sh`/`stop.sh`（停机 ≤40s 无 SIGKILL 路径日志）、docker compose up/down 行为与交付前一致；全量既有测试绿。

## SC 对照

SC-001→V2；SC-002/003/004→V3；SC-005→V4 + NOOP 单测；SC-006→V5（+027 卷互链）；SC-007→V1 进 ci + V6。
