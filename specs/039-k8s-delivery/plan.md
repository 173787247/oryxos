# Implementation Plan: 容器交付（K8s Delivery）

**Branch**: `039-k8s-delivery` | **Date**: 2026-09-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/039-k8s-delivery/spec.md`

## Summary

v0.4 闭环刀：官方 Helm Chart（副本可调、Secret 注入、RWX PVC、actuator 双探针、preStop 优雅）把前三刀装进一条命令部署的 K8s 载体；补齐三处实证停机缺口（`server.shutdown: graceful`、`stopAll` 漏释放渠道属主租约、stop.sh 宽限期不一致）让滚动升级零失败成立；OTel trace 导出沿 MetricsRecorder 同款依赖倒置纪律（core 契约 `SpanRecorder` + 装配层 OTel SDK 实现，traceId 与 021 审计同源、事后补记式 span、不配零开销）；验收两档（无集群门禁自动化 lint/template/kubeconform + kind 真机走查落卷）并兑现 026 吞吐线性性、027 RWX 抽查两笔留账。

## Technical Context

**Language/Version**: Java 21；Chart 为 Helm 3 模板（Go template + YAML）

**Primary Dependencies**: 新增 `opentelemetry-sdk` + `opentelemetry-exporter-otlp`（装配层，调查确认全仓零冲突构件）；helm/kubeconform/kind 为构建与验收期工具（用户目录安装，不进运行时）

**Storage**: 无新库表（trace 不落库，span 与既有审计三表同 traceId 互查）；工作区经 RWX PVC（027 支持矩阵）

**Testing**: 门禁档——helm lint + template 渲染断言 + kubeconform（make 目标 + ci job）、SpanRecorder 单测（fake exporter）、停机行为既有 IT 回归；真机档——kind 双副本安装冒烟、滚动升级持续压测零失败、kill pod 接管、US4 两笔留账复测

**Target Platform**: K8s ≥1.27（kind/k3d 本地验收，真云 RWX 按 SharedVolumeGuide）；既有裸机/compose 形态零变化

**Project Type**: Maven 多模块 + 顶层 `charts/oryxos/` 新目录（非 Maven 模块）

**Performance Goals**: 滚动升级期间 ≥1 req/s 持续请求零失败（SC-002）；OTel NOOP 档零开销、启用档异步批量导出不进请求路径

**Constraints**: 宪法 VII（导出走 SDK 后台批处理，核心循环仍同步）；FR-010 零破坏面（R9 清单）；本机无 docker/kind 的环境阶梯（R6）

**Scale/Scope**: 双副本为验收基线；改动集中在 charts/（新）+ boot 配置两行 + core 契约一个 + 三个调用点 + cli 装配/实现 + 两处停机修复 + CI/Makefile/docs

## Constitution Check

| 原则 | 判定 | 说明 |
|------|------|------|
| I 自实现 ReAct | ✅ 不涉 | span 调用点在循环外围计时区间，不改循环逻辑 |
| II Spring AI 两件事 | ✅ 不涉 | — |
| III Provider 显式映射 | ✅ 不涉 | mock 时延参数走既有 provider 配置面 |
| IV 目录=Agent | ✅ 不涉 | — |
| V 审计 Day One | ✅ 合规 | 审计三表不动；OTel 是并行叠加（同 traceId），绝不替代落库 |
| VI 安全地基 | ✅ 合规 | 凭证走 K8s Secret→环境变量（022 原生面）；Chart 模板零明文凭证；OTLP endpoint 为管理员显式配置的出站地址（部署配置面，非 Tool 面不涉沙箱） |
| VII 同步 + 虚拟线程 | ✅ 合规 | span 事后补记同步调用；BatchSpanProcessor 为 SDK 内部后台导出线程，不引入核心异步编程模型（与 Micrometer/Prometheus 抓取同性质） |
| VIII 状态外置 + Flyway | ✅ 合规 | 无新表；无迁移 |
| 模块演进条款 | ✅ 合规 | 无新 Maven 模块；`charts/` 为部署资产目录（同 `docker/` 性质）；SpanRecorder 契约在 core、实现在装配层（依赖倒置） |

**Post-Phase-1 re-check**: ✅ 通过（Complexity Tracking 空）。

## Project Structure

### Documentation (this feature)

```text
specs/039-k8s-delivery/
├── plan.md / research.md（R1~R9） / data-model.md / quickstart.md
├── contracts/（span-recorder.md + helm-values.md）
└── tasks.md（/speckit-tasks 生成）
```

### Source Code (repository root)

```text
charts/oryxos/                          # [新] Helm Chart（非 Maven 模块，同 docker/ 性质）
├── Chart.yaml                          # version 0.1.0；appVersion 跟随 pom
├── values.yaml                         # 契约见 contracts/helm-values.md
├── templates/
│   ├── deployment.yaml                 # 副本/探针/preStop/grace 40s/Secret env/ConfigMap 挂载/PVC 挂载
│   ├── service.yaml
│   ├── configmap.yaml                  # /data/config/application.yml（cluster.enabled=true、
│   │                                   #   timeout-per-shutdown-phase 30s、可选 otel.endpoint）
│   ├── secret.yaml                     # 仅 values 直填时渲染；推荐 existingSecret 引用
│   ├── pvc.yaml                        # RWX；storageClass 可配；replicas>1 且非 RWX 时 fail 模板断言
│   ├── _helpers.tpl / NOTES.txt
└── ci/default-values-test.yaml         # 门禁渲染断言用固定 values

oryxos-core/src/main/java/io/oryxos/core/
├── metrics/SpanRecorder.java           # [新] 契约：recordTurnSpan/recordLlmSpan/recordToolSpan + NOOP
├── agent/AgentService.java             # [改] 单轮 Scope 块同址补记 turn span（成功/失败分支）
├── agent/ToolExecutor.java             # [改] auditor.record 区间同址补记 tool span
└── channel/ChannelAdminService.java    # [改] stopAll 补 coordinator.unmanage()（停机释放属主租约）

oryxos-provider/src/main/java/io/oryxos/provider/
├── SpringAiProviderServiceImpl.java    # [改] 成功/失败路径同址补记 llm span
└── （mock provider 实现处）             # [改] 可选 mock-latency-ms（默认 0 零变化，US4 压测用）

oryxos-cli/src/main/java/io/oryxos/cli/
├── OtelSpanRecorder.java               # [新] OTel SDK 实现：traceId→32hex、turn spanId=前16hex 确定性父子、
│                                       #   BatchSpanProcessor 异步导出、异常自吞
├── OtelProperties.java                 # [新] oryxos.otel.endpoint / sampler-ratio（空端点→NOOP 装配）
└── OryxOsRuntime.java                  # [改] SpanRecorder bean（endpoint 空=NOOP）+ 三调用点注入

oryxos-boot/src/main/resources/application.yml   # [改] 两行：server.shutdown=graceful、
                                                 #   readiness 组 include: readinessState,db
oryxos-cli/pom.xml                      # [改] opentelemetry-sdk + exporter-otlp

bin/stop.sh                             # [改] SIGTERM 等待 10s→40s（对齐 compose 实测口径）
Makefile                                # [改] helm-lint / helm-package 目标
.github/workflows/ci.yml               # [改] 新 job：helm lint + template + kubeconform（只验不推）
.github/workflows/release.yml          # [改] chart tgz 上传至 Release 附件（与 tar.gz 并列）
docs/K8sDeployGuide.md                  # [新] 一条命令安装/values/RWX 要求/升级回滚/排查
docs/SharedVolumeGuide.md               # [改] 补 K8s RWX PVC 实操一节互链

测试（关键新增）：
oryxos-core/src/test  → SpanRecorder 调用点单测（fake recorder 断言三类 span 时序与属性）
oryxos-cli/src/test   → OtelSpanRecorder 单测（InMemorySpanExporter：traceId 映射/父子/时间戳/NOOP 档零导出）
scripts/helm-verify.sh → 门禁断言脚本（template 渲染的探针路径/grace/RWX/Secret 引用）
真机档走查步骤在 quickstart.md（kind 安装冒烟/滚动升级压测/kill 接管/US4 留账）
```

**Structure Decision**: 无新 Maven 模块；`charts/` 与 `docker/` 同级同性质（部署资产）。SpanRecorder 走 MetricsRecorder 完全同款分层（core 契约 + cli 装配实现 + 调用点显式调用零 AOP）。停机三修（graceful/stopAll 租约/stop.sh 宽限）均为实证缺口修复，列入零破坏声明（R9）。

## 实现要点（按 US 分组）

### US1 一条命令部署（P1）
1. Chart 骨架 + values 契约（必填 existingSecret/db；可选 replicas/资源/探针/优雅窗口/otel）；模板断言：replicas>1 时 PVC 必须 RWX。
2. ConfigMap 注入 cluster.enabled/timeout 30s；Secret env 注入 SPRING_DATASOURCE_* 与 ORYXOS_MASTER_KEY；instance-id 走默认 Pod 名派生（R2）。
3. NOTES.txt 输出访问方式与 instances 检查命令；缺必填 values 时 `required` 函数安装期报错（FR-006）。
4. 门禁：helm lint + template + kubeconform + 断言脚本进 make/ci。

### US2 滚动升级与故障（P1）
5. boot 补 `server.shutdown: graceful`；readiness 组含 db（R3）。
6. `ChannelAdminService.stopAll()` 补 unmanage（先释放属主租约再断连）；配套单测。
7. Chart：RollingUpdate maxUnavailable=0 maxSurge=1、preStop sleep 5、terminationGracePeriodSeconds 40。
8. `bin/stop.sh` 宽限 10s→40s。
9. 真机：升级期间持续压测零失败、kill pod 接管、preStop 期间渠道租约释放日志抽查。

### US3 OTel 导出（P2）
10. `SpanRecorder` 契约 + 三调用点补记（AgentService/Provider/ToolExecutor 既有计时区间同址）。
11. `OtelSpanRecorder` + `OtelProperties` + 装配（endpoint 空=NOOP）；cli pom 增依赖。
12. 单测双档：InMemory exporter 断言链路结构与 traceId 同源；NOOP 档零导出零连接。
13. 真机：kind 里起 OTLP collector（或 Jaeger all-in-one）验证一轮链路可视 + 与 audit/trace 接口互查。

### US4 留账兑现（P3）
14. mock provider `mock-latency-ms`（默认 0）；独立发压脚本；单/双副本吞吐对比落卷。
15. RWX（NFS provisioner）上复跑 027 可见性走查；027 验收卷补记互链。

### Polish
16. docs（K8sDeployGuide + SharedVolumeGuide 互链 + CLAUDE.md/CliGuide 同步）；release.yml chart 附件；全量门禁 + acceptance-report。

## Complexity Tracking

> 无宪法违背，无需填写。
