# Data Model: 容器交付（039-k8s-delivery）

**Date**: 2026-09-14 | **迁移**: 无（本刀零新库表、零 Flyway 变更）

## 1. 库表面：不变

- 审计三表（`llm_calls` / `tool_invocations` / `agent_executions`）结构与写入路径不动；OTel span 与之同 traceId 互查（FR-009），**span 不落库**。
- 026/027 协调表不动；K8s 生命周期只影响「谁在调用既有释放/过期路径」（stopAll 补 unmanage、TTL 兜底），不改表语义。

## 2. 部署资产模型（Chart 内实体，非库表）

| 实体 | 载体 | 关键字段/约定 |
|------|------|--------------|
| Release | helm release | 副本数、镜像 tag、Secret 引用、PVC 声明；升级/回滚单位 |
| values | values.yaml | 契约见 contracts/helm-values.md（必填 2 项 + 可选默认） |
| ConfigMap | `/data/config/application.yml` | cluster.enabled=true、drainTimeout、可选 otel.endpoint、extraConfig 合并 |
| Secret | 引用式（推荐）/渲染式 | `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`、`ORYXOS_MASTER_KEY` 四键 |
| PVC | RWX | 工作区 `.oryxos`（027 支持矩阵）；replicas>1 强制 RWX 模板断言 |

## 3. Trace 链路模型（内存/导出，非库表）

```
turn (root)  spanId = traceId[0:16]（确定性）
├── llm-call ×N   parent = turn
└── tool ×M       parent = turn
```

- TraceId = 021 UUID 去横线 32 hex（与审计表 trace_id 同源可互查）
- 时间戳 = 各调用点既有 startedAt/durationMs（与审计耗时同源）
- 属性集见 contracts/span-recorder.md；失败 span 置 ERROR 状态

## 4. 配置项增量

| 键 | 默认 | 归属 |
|----|------|------|
| `oryxos.otel.endpoint` | `""`（NOOP） | OtelProperties（cli 装配层） |
| `oryxos.otel.sampler-ratio` | `1.0` | 同上 |
| `oryxos.providers[].mock-latency-ms` | `0`（零变化） | mock provider（US4 压测用） |
| `server.shutdown` | `graceful` | boot application.yml（新增行） |
| `management.endpoint.health.group.readiness.include` | `readinessState,db` | boot application.yml（新增行） |
