# Contract: Helm values（charts/oryxos）

**用户配置面**（`helm install oryxos charts/oryxos -f my-values.yaml`）。缺必填项时 `required` 模板函数在安装期报错并指出缺失键（FR-006）。

## 必填

| 键 | 说明 |
|----|------|
| `database.existingSecret` | 含 `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` 三键的 Secret 名（推荐）；或改用 `database.url/username/password` 由 chart 渲染 Secret（值仍不落 ConfigMap） |
| `masterKey.existingSecret` | 含 `ORYXOS_MASTER_KEY` 键的 Secret 名（022 环境变量档）；或 `masterKey.value` 由 chart 渲染 Secret |

## 常用可选（默认值）

| 键 | 默认 | 说明 |
|----|------|------|
| `replicaCount` | `2` | 副本数；`1` 亦为合法配置（集群档自洽） |
| `image.repository` / `image.tag` | `ghcr.io/oryx-labs/oryxos` / Chart appVersion | 镜像 |
| `workspace.storageClassName` | `""`（集群默认） | 工作区 PVC 存储类；**replicaCount>1 时必须支持 RWX**（模板断言 accessMode） |
| `workspace.size` | `5Gi` | PVC 容量 |
| `resources` | requests 512Mi/250m，limits 2Gi/2 | 容器资源 |
| `shutdown.gracePeriodSeconds` | `40` | terminationGracePeriodSeconds（对齐 compose 实测口径） |
| `shutdown.drainTimeout` | `30s` | 注入 ConfigMap 的 timeout-per-shutdown-phase（适配真实 LLM 轮次） |
| `probes.liveness.path` / `probes.readiness.path` | `/actuator/health/liveness` / `/actuator/health/readiness` | 探针端点（免认证已豁免）；initialDelay/period 可调 |
| `otel.endpoint` | `""`（禁用） | OTLP gRPC 端点；空=零开销 NOOP |
| `otel.samplerRatio` | `1.0` | 采样比 |
| `service.type` / `service.port` | `ClusterIP` / `8080` | 服务暴露 |
| `extraConfig` | `{}` | 追加合并进 `/data/config/application.yml` 的任意配置段（如 providers） |

## 模板行为约定

- Deployment：`strategy: RollingUpdate{maxUnavailable: 0, maxSurge: 1}`；`preStop: sleep 5`；探针双配；env 来自 Secret（envFrom/valueFrom），非敏感配置全走 ConfigMap 挂载 `/data/config/application.yml`
- ConfigMap 固定注入：`oryxos.cluster.enabled: true`、`spring.lifecycle.timeout-per-shutdown-phase: {{ .Values.shutdown.drainTimeout }}`、（otel.endpoint 非空时）`oryxos.otel.endpoint`
- 零明文纪律：任何凭证类值不得出现在 ConfigMap/注解/NOTES 输出中
- `helm template` 在 `replicaCount>1` 且 PVC accessMode 非 RWX 时 fail（`fail` 函数），错误信息指向 SharedVolumeGuide

## 门禁断言（scripts/helm-verify.sh，进 make helm-lint 与 ci）

1. `helm lint` 零 error
2. `helm template -f ci/default-values-test.yaml` 渲染成功且 kubeconform 校验通过（K8s 1.27+ schema）
3. 渲染产物断言：replicas=2、双探针路径正确、grace=40、preStop 存在、PVC accessModes=[ReadWriteMany]、无明文凭证串、缺必填 values 时渲染失败并含指引文案
