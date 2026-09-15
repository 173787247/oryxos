# K8s 部署指南（039 容器交付）

OryxOS 的第三种部署形态：官方 Helm Chart（`charts/oryxos/`）。裸机 tar.gz 与 docker compose 形态不变——Helm 是新增选项，不是归顺 K8s（裸机 / VM / K8s 都是一等形态）。

## 一条命令安装

前提：K8s ≥1.27、共享 PostgreSQL（集群外或自备）、多副本时 RWX 存储类（见 `SharedVolumeGuide.md`）。

```bash
# 1) 两项密文（唯一必填）
kubectl create secret generic oryxos-db \
  --from-literal=SPRING_DATASOURCE_URL='jdbc:postgresql://<host>:5432/<db>' \
  --from-literal=SPRING_DATASOURCE_USERNAME='<user>' \
  --from-literal=SPRING_DATASOURCE_PASSWORD='<pass>'
kubectl create secret generic oryxos-master-key \
  --from-literal=ORYXOS_MASTER_KEY="$(openssl rand -base64 32)"

# 2) 安装（默认双副本；chart 也随 GitHub Release 提供 tgz 附件）
helm install oryxos charts/oryxos \
  --set database.existingSecret=oryxos-db \
  --set masterKey.existingSecret=oryxos-master-key

# 3) 验证
kubectl rollout status deploy/oryxos
kubectl port-forward svc/oryxos 8080:8080 &
curl -s localhost:8080/api/v1/instances   # 双副本 alive、clusterEnabled=true
```

缺必填项时安装期即报错并指出缺失键（不部署带病实例）。

## 常用 values

| 键 | 默认 | 说明 |
|----|------|------|
| `replicaCount` | 2 | 副本数（1 亦合法；>1 强制 RWX 工作区） |
| `image.repository`/`image.tag` | ghcr.io/oryx-labs/oryxos / appVersion | 镜像 |
| `workspace.storageClassName`/`size` | 集群默认 / 5Gi | 工作区 RWX PVC（027 共享卷） |
| `shutdown.gracePeriodSeconds` | 40 | 终止宽限（与 compose 同口径） |
| `shutdown.drainTimeout` | 30s | 在途请求排空 + 停机阶段上限（适配真实 LLM 轮次） |
| `otel.endpoint` | ""（禁用） | OTLP gRPC 端点（如 `http://jaeger:4317`）；配置即导出 trace（traceId 与 `/api/v1/audit/trace/{id}` 同源互查），不配零开销 |
| `otel.metricsEndpoint` | ""（禁用） | #471：指标 OTLP/HTTP 端点（如 `http://collector:4318/v1/metrics`）——LLM 时延/token/成本/错误与 JVM/HTTP 全量指标推送 OTel 后端（`service.name=oryxos` 与 trace 关联）；Prometheus 拉取口径不受影响 |
| `resources` | 512Mi/250m ~ 2Gi/2 | 容器资源 |
| `extraConfig` | {} | 合并进 `/data/config/application.yml` 的任意段（providers/embedding 等） |

完整契约：`specs/039-k8s-delivery/contracts/helm-values.md`；渲染断言：`make helm-lint`。

## 升级与回滚

```bash
helm upgrade oryxos charts/oryxos --reuse-values --set image.tag=v0.1.6-RELEASE
helm rollback oryxos            # 回上一版
```

滚动策略 `maxUnavailable=0, maxSurge=1`：先起新副本、旧副本收到终止信号后先摘流量（readiness）→ preStop 5s 等 endpoint 摘除传播 → graceful 排空在途请求（drainTimeout）→ 释放渠道属主租约与在途轮次收尾 → 退出。升级期间连续请求零失败为验收口径（`scripts/rolling-probe.sh` 可自测）。新旧版本短暂共存安全（Flyway V6+ 前向兼容迁移纪律；含新迁移时由启动锁串行化）。

## 探针语义

- **liveness** `/actuator/health/liveness`：仅进程活性（DB 抖动不触发重启）。
- **readiness** `/actuator/health/readiness`：状态位 + **数据库可达**——DB 断连的副本被摘流不接新请求。
- 两端点免认证；`/api/v1/health` 保持常量 200（裸机/compose 探活口径不变）。

## 排查入口

| 症状 | 检查 |
|------|------|
| Pod CrashLoopBackOff | `kubectl logs`：主密钥不匹配（022 指路恢复）/ 集群档误配（026 拒启文案）/ DB 不可达 |
| Pod Running 但 0/1 Ready | readiness 含 DB——确认 `SPRING_DATASOURCE_*` 与网络策略；`kubectl describe pod` 看探针输出 |
| 第二副本 Pending | 多副本 + 非 RWX 存储类：PVC 绑定失败，见 `SharedVolumeGuide.md` |
| 安装即报错 | 缺 `database.*`/`masterKey.*` 必填（报错文案指路本文档） |
| A 建 Agent B 不可见 | 确认工作区确为共享 PVC（两 Pod 同一 claim）；直接改盘走 `POST /api/v1/workspace/refresh` |

## 本地验收（kind）

`scripts/kind-smoke.sh <image:tag>` 一键完成：附带 PG → 密文 → 单节点 RWX（hostPath 静态 PV）→ install → 双副本就绪 → 实例双活 → 跨副本可见性断言。CI 的 helm job 对每个 PR 自动执行同一脚本。工具安装：`scripts/install-k8s-tools.sh all`（kind 需 docker，WSL2 用户见 `specs/039-k8s-delivery/quickstart.md` V0 环境阶梯）。
