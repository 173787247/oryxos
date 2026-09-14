# Tasks: 容器交付（K8s Delivery）

**Input**: Design documents from `/specs/039-k8s-delivery/`

**Prerequisites**: plan.md、spec.md、research.md（R1~R9）、data-model.md、contracts/、quickstart.md

**Tests**: 包含测试任务——spec 各 US 定义了 Independent Test，quickstart 定义了两档验收，宪法门禁要求全绿。

**Organization**: 按 User Story 分阶段；真机档任务标注「环境阶梯」（quickstart V0）——环境不可得时完成门禁档并如实记录待补。

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

- [ ] T001 新建 `scripts/install-k8s-tools.sh`（helm/kubeconform/kind/kubectl 按需 curl 安装到 `~/bin`，幂等、校验版本），并在本机执行安装 helm + kubeconform（门禁档必需；kind/kubectl 留真机档按 V0 阶梯）
- [ ] T002 [P] `oryxos-cli/pom.xml` 新增 `opentelemetry-sdk` + `opentelemetry-exporter-otlp`（版本对齐 BOM，OWASP 门禁过）

---

## Phase 2: Foundational（阻塞 US3；US1/US2 不依赖）

- [ ] T003 新建 `oryxos-core/src/main/java/io/oryxos/core/metrics/SpanRecorder.java`：三 default 方法 + NOOP 常量（契约见 contracts/span-recorder.md，MetricsRecorder 同款纪律）
- [ ] T004 新建 `oryxos-cli/src/main/java/io/oryxos/cli/OtelProperties.java`（`oryxos.otel.endpoint`/`sampler-ratio`）与 `OtelSpanRecorder.java`（traceId 去横线映射、turn spanId=前 16 hex 确定性父子、显式起止时间、BatchSpanProcessor、异常自吞）+ 单测（InMemorySpanExporter：链路结构/时间戳/非法 traceId 不炸）
- [ ] T005 `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java` 装配 SpanRecorder bean（endpoint 空=NOOP 零初始化）+ 关闭时 flush/shutdown SDK（ContextClosedEvent 监听扩展）

**Checkpoint**: span 契约与导出实现就绪；US1/US2/US3 三线可并行

---

## Phase 3: User Story 1 - 一条命令在 K8s 上部署多副本（P1）🎯 MVP

**Goal**: `helm install` 双副本全绿；门禁档 lint/template/schema/断言全自动。

**Independent Test**: `make helm-lint` 全绿（无集群）；真机 kind 安装冒烟 ≤5min 双副本就绪（环境阶梯）。

- [ ] T006 [US1] 新建 `charts/oryxos/Chart.yaml`、`values.yaml`（契约见 contracts/helm-values.md：必填 database/masterKey existingSecret、默认 replicaCount=2 等）、`templates/_helpers.tpl`、`templates/NOTES.txt`（访问方式 + instances 检查命令）
- [ ] T007 [US1] 新建 `charts/oryxos/templates/deployment.yaml`：RollingUpdate(maxUnavailable=0,maxSurge=1)、liveness/readiness 探针（`/actuator/health/liveness|readiness`）、`preStop: sleep 5`、`terminationGracePeriodSeconds: {{ .Values.shutdown.gracePeriodSeconds }}`、Secret env 注入（SPRING_DATASOURCE_* + ORYXOS_MASTER_KEY）、ConfigMap 挂 `/data/config/`、PVC 挂 `/data/.oryxos`
- [ ] T008 [P] [US1] 新建 `templates/service.yaml`、`templates/configmap.yaml`（cluster.enabled=true、timeout-per-shutdown-phase={{drainTimeout}}、可选 otel.endpoint、extraConfig 合并）、`templates/secret.yaml`（仅 values 直填时渲染）、`templates/pvc.yaml`（replicaCount>1 且非 RWX 时 `fail` 指向 SharedVolumeGuide；缺必填 values 时 `required` 报错）
- [ ] T009 [US1] 新建 `charts/oryxos/ci/default-values-test.yaml` 与 `scripts/helm-verify.sh`：template 渲染断言（副本 2/双探针路径/grace 40/preStop/RWX/零明文凭证/缺必填渲染失败带指引）+ kubeconform 校验
- [ ] T010 [US1] `Makefile` 新增 `helm-lint`/`helm-package` 目标；`.github/workflows/ci.yml` 新增 helm job（lint+template+kubeconform+verify，只验不推，与 docker-build 同格）
- [ ] T011 [US1] 本机执行 `make helm-lint` 全绿（门禁档验收锚点，SC-007 自动化面）

**Checkpoint**: US1 门禁档独立可交付；真机冒烟并入 T015

---

## Phase 4: User Story 2 - 滚动升级与故障不打断在途对话（P1）

**Goal**: 三处实证停机缺口修复 + K8s 生命周期正确对接。

**Independent Test**: stopAll 释放租约单测 + 停机行为回归；真机滚动升级零失败/kill 接管（环境阶梯）。

- [ ] T012 [US2] `oryxos-boot/src/main/resources/application.yml` 补两行：`server.shutdown: graceful`、`management.endpoint.health.group.readiness.include: readinessState,db`；核对既有 boot 测试（100ms 停机参数用例）零回归，必要处适配
- [ ] T013 [US2] `oryxos-core/src/main/java/io/oryxos/core/channel/ChannelAdminService.java` 修 `stopAll()`：与 `stopOne()` 对齐，先 `coordinator.unmanage()`（cancel 续租 + releaseChannel）再 `adapter.stop()`；配套单测（停机路径属主租约被释放、单机档无 coordinator 时零变化）
- [ ] T014 [P] [US2] `bin/stop.sh` SIGTERM 等待 10s→40s（对齐 compose `stop_grace_period` 与实测 ~32s 口径），注释说明来源
- [ ] T015 [US2] 真机走查（环境阶梯 V0→V2/V3）：kind 双副本安装冒烟 ≤5min（含 US1 场景 1/2/4）、`scripts/rolling-probe.sh` 持续压测下 `helm upgrade` 零失败、`kubectl delete pod --force` 后接管与归队、preStop 日志含渠道租约释放；环境不可得时如实记录门禁档证据与待补项

**Checkpoint**: US2 代码面独立可交付（T012~T014 不依赖 Chart）；真机面依赖 US1 Chart

---

## Phase 5: User Story 3 - 内生 trace 接入观测栈（P2）

**Goal**: 三调用点事后补记 span，traceId 与审计同源；不配零开销。

**Independent Test**: fake recorder 单测三点位时序；InMemory exporter 链路断言；NOOP 档全量回归；真机 Jaeger 链路（环境阶梯）。

- [ ] T016 [US3] `oryxos-core/src/main/java/io/oryxos/core/agent/AgentService.java` 单轮 Scope 块同址补记 turn span（成功/失败分支各一次，setter 注入 SpanRecorder 默认 NOOP）；配套单测（fake recorder）
- [ ] T017 [P] [US3] `oryxos-provider/src/main/java/io/oryxos/provider/SpringAiProviderServiceImpl.java` 成功/失败路径既有 startedAt/durationMs 区间同址补记 llm span（构造注入 SpanRecorder，装配点同步改）；配套单测
- [ ] T018 [P] [US3] `oryxos-core/src/main/java/io/oryxos/core/agent/ToolExecutor.java` auditor.record 区间同址补记 tool span（含策略拦截 blocked=true 路径，setter 注入同 MetricsRecorder）；配套单测
- [ ] T019 [US3] NOOP 档零变化断言（endpoint 未配时装配为 NOOP 的装配测试 + 全量既有测试回归）；真机 V4：kind 内起 Jaeger all-in-one，`--set otel.endpoint` 后一轮含工具对话链路可视、traceId 与 `GET /api/v1/audit/trace/{id}` 互查一致（环境阶梯）

**Checkpoint**: US3 独立可交付

---

## Phase 6: User Story 4 - 前刀留账兑现（P3）

**Goal**: 026 吞吐线性性硬数字 + 027 RWX 抽查，如实落卷。

**Independent Test**: 见 quickstart V5 两条复测步骤。

- [ ] T020 [US4] mock provider 增加可选 `mock-latency-ms`（默认 0 零变化；实现文件按现状定位于 oryxos-provider mock 实现处）+ 单测（0 时零延迟路径不变、>0 时生效）
- [ ] T021 [P] [US4] 新建 `scripts/rolling-probe.sh`（固定速率探测记录每请求结果，US2 复用）与 `scripts/load-test.sh`（集群外并发发压，输出吞吐统计）
- [ ] T022 [US4] 真机复测落卷（环境阶梯）：`mock-latency-ms=800` 下单/双副本吞吐比（目标 ≥1.6×，环境局限如实标注）；RWX（NFS provisioner）上复跑 027 A 建 B 见 + 改盘 refresh 走查，结果回写 `specs/027-file-plane/acceptance-report.md` 互链

**Checkpoint**: US4 独立可交付（依赖 US1 Chart + 环境）

---

## Phase 7: Polish & Cross-Cutting

- [ ] T023 文档同步：新建 `docs/K8sDeployGuide.md`（一条命令安装/values 说明/RWX 要求/升级回滚/排查：探针失败/CrashLoop/密钥错配）；`docs/SharedVolumeGuide.md` 补 K8s RWX PVC 实操一节互链；CLAUDE.md 与 `docs/CliGuide.md` 补 039 一段（部署选项 + otel 配置 + 停机口径统一说明）
- [ ] T024 [P] `.github/workflows/release.yml` 追加 `helm package` 产物上传至 GitHub Release 附件（与 tar.gz 并列；chart appVersion 对齐 pom 版本口径）
- [ ] T025 全量门禁 + 验收落卷：`mvn clean install` BUILD SUCCESS + `make helm-lint` 全绿 + 显式 IT 回归（026/027 套件零回归，裸机 stop.sh 停机 ≤40s 抽查）；按 quickstart V2~V6 真机走查（环境阶梯，如实记录）；撰写 `specs/039-k8s-delivery/acceptance-report.md`（SC-001~007 对照）

---

## Dependencies

```
Phase 1 (T001,T002) → Phase 2 (T003→T004→T005，仅阻塞 US3)
US1 (T006→T007/T008→T009→T010→T011)：不依赖 Phase 2，可与其并行
US2 代码面 (T012/T013/T014 互相独立并行)：不依赖 Chart；T015 真机依赖 T011 + 环境阶梯
US3 (T016/T017/T018 并行，均依赖 T003~T005 → T019)
US4 (T020/T021 并行 → T022 依赖 T011+T015 环境)
Phase 7：T023/T024 可随时并行；T025 收尾最后
```

## Parallel Execution Examples

- 开局三线：T001（工具脚本）∥ T002+T003~T005（OTel 链）∥ T006~T008（Chart 主体）
- US2 代码面三任务（T012/T013/T014）完全并行且不等 Chart
- US3 三调用点（T016/T017/T018）三文件并行
- 真机档（T015/T019 后半/T022）串在环境就绪后一次走查完成

## Implementation Strategy

- **MVP = US1 门禁档（T006~T011）+ US2 代码面（T012~T014）**：Chart 可安装 + 停机缺口修复即为最小可交付
- 真机档三处（T015/T019/T022）合并为一次 kind 会话完成，环境获取按 V0 阶梯（先自装，docker 不可得时向用户请求开启 Docker Desktop WSL integration）
- 每 Checkpoint 全量绿 + 一次提交；裸机/compose 零变化面（R9 清单）为固定回归断言
