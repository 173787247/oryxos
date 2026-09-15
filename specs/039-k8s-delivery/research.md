# Research: 容器交付（039-k8s-delivery）

**Date**: 2026-09-14 | **Input**: spec.md + 代码面调查（Docker/停机/探针/trace 现状与五个缺口）

## R1. Chart 形态与配置注入

- **Decision**: 新增顶层 `charts/oryxos/`（Chart.yaml + values.yaml + templates：Deployment/Service/ConfigMap/PVC/NOTES/_helpers；Secret 支持「引用已有」与「由 values 创建」两式，推荐前者）。配置注入两路复用既有机制零改动：**非敏感配置**经 ConfigMap 挂载 `/data/config/application.yml`（entrypoint 既有 `spring.config.additional-location` 直接生效）；**敏感项**经 Secret 注环境变量——`SPRING_DATASOURCE_URL/USERNAME/PASSWORD`（Spring relaxed binding 原生）与 `ORYXOS_MASTER_KEY`（022 原生）。
- **Rationale**: 镜像/entrypoint 完全不动（调查确认 `/data/config/` 附加位置与 env 注入面已就绪）；Secret 引用式符合企业密文管理惯例。
- **Alternatives considered**: 全部走 env relaxed binding（cluster.enabled 等嵌套项书写易错、可读性差，拒）；Operator/CRD（明确不做）。

## R2. 实例标识与集群档开启

- **Decision**: Chart 的 ConfigMap 里固定 `oryxos.cluster.enabled: true`；**instance-id 不显式配置**——`ClusterProperties.defaultInstanceId()` 已是「主机名-pid」，容器内即「Pod 名-1」天然唯一（026 已文档化此行为）。owner 的 epoch 代次隔离保证 Pod 重建不误继承。
- **Rationale**: 少一个必填项；与既有默认行为完全一致，零新机制。
- **Alternatives considered**: fieldRef 注入 Pod 名到专用 env——relaxed binding 对 `instance-id` 的 env 名易错（`ORYXOS_CLUSTER_INSTANCEID`），且无增益，拒。

## R3. 探针与就绪门控

- **Decision**: liveness=`/actuator/health/liveness`、readiness=`/actuator/health/readiness`（`probes.enabled: true` 已配，端点已免认证豁免）。boot 默认配置补一行 readiness 分组：`management.endpoint.health.group.readiness.include: readinessState,db`——就绪含数据库可达（调查缺口③：现状 readiness 只看状态位，DB 断连的副本仍显示就绪）。`/api/v1/health` 常量 200 保持不动（FR-010：裸机/compose 探活口径零变化）。启动时序无需处理：Flyway 与注册表构建都在端口 accept 之前同步完成（调查已证）。
- **Rationale**: K8s 语义下 readiness 必须反映「能否正确服务」，DB 是唯一硬依赖；改动一行且对裸机仅影响 actuator 子端点内容。
- **Alternatives considered**: 自写 HealthIndicator 聚合渠道/Provider 状态——渠道单条失败本就不阻断启动（既有裁决），纳入就绪会放大故障面，拒；readiness 含共享卷探测——027 已裁决不做卷探测，拒。

## R4. 优雅停机与租约释放（US2 核心，三处补齐）

- **Decision**:
  1. boot 默认配置补 `server.shutdown: graceful`（调查缺口①：现状无 web 请求排空）——排空窗口即 `spring.lifecycle.timeout-per-shutdown-phase`（默认 10s 保持；Chart 的 ConfigMap 将其调至 30s 适配真实 LLM 轮次）。
  2. **修 `ChannelAdminService.stopAll()` 漏释放渠道属主租约**（调查缺口②）：stopAll 与 stopOne 对齐，先 `coordinator.unmanage()`（cancel 续租 + releaseChannel）再 stop adapter——属主 Pod 退出后新属主立刻可接管，不等 TTL。turn 租约维持既有语义：排空窗口内正常轮次走 `AgentService` finally 释放；窗口外靠 TTL 过期 + 026 惰性回收（spec Edge Case 明确不无限等待）。
  3. Chart：`terminationGracePeriodSeconds: 40`（与 compose 40s 同口径）+ `preStop: sleep 5`（等 endpoint 摘除传播，K8s 标准手法）；**顺手修 `bin/stop.sh` 等待 10s→40s**（调查缺口④：与 compose/实测 ~32s 不一致，现状大概率 SIGKILL——属 bug 修复非行为变更，tar.gz 停机语义反而回归文档承诺）。
- **Rationale**: 三处都有实证缺口支撑；渠道租约显式释放把企微接管从「等 30s TTL」缩到秒级。
- **Alternatives considered**: preStop 里调应用「主动排空」端点——server.shutdown=graceful 已由 Spring 标准机制覆盖，自造排空协议是重复，拒；停机时强制释放在途 turn 租约——会把「处理中」轮次误标可抢，正确语义就是 TTL 兜底，拒。

## R5. OTel 导出的接入形态（US3）

- **Decision**: 沿 **MetricsRecorder 同款纪律**新增 core 契约 `SpanRecorder`（全 default 空方法 + NOOP 常量，依赖倒置）：
  - `recordTurnSpan(traceId, agentName, channel, success, startEpochMs, durationMs)`
  - `recordLlmSpan(traceId, provider, model, success, startEpochMs, durationMs)`
  - `recordToolSpan(traceId, toolName, success, blockedByPolicy, startEpochMs, durationMs)`
  - 调用点与审计同址（调查确认三处均已有 startedAt/durationMs 计时区间与显式调用风格，零 AOP）：`AgentService` 单轮 Scope 块、`SpringAiProviderServiceImpl` 成功/失败路径、`ToolExecutor` record 区间。**事后补记式 span**（显式起止时间，OTel SDK 原生支持），不引入任何上下文传播新机制。
  - **父子关系确定性推导**：OTel traceId = 021 UUID 去横线的 32 hex（长度恰好匹配）；turn 根 span 的 spanId = traceId 前 16 hex（确定性），LLM/工具子 span 以之为 parentSpanId、自身随机——三个记录点无需互相传递句柄。
  - 实现 `OtelSpanRecorder` 在装配层（oryxos-cli），依赖 `opentelemetry-sdk` + `opentelemetry-exporter-otlp`（调查确认全仓零 OTel/micrometer-tracing 构件，纯新增无冲突）；BatchSpanProcessor 异步批量导出（后台 daemon，不在请求路径同步等待——不属于核心循环的异步编程模型，宪法 VII 合规）。
  - 配置 `oryxos.otel.endpoint`（空=默认：装配 NOOP，零依赖零连接）+ `oryxos.otel.sampler-ratio`（默认 1.0）。
- **Rationale**: 与 021「审计供精确回放、OTel 供跨系统链路，同源不同面」的裁决严格一致；traceId 直接互查（SC-005/FR-009）；NOOP 档零开销（FR-008）。
- **Alternatives considered**: micrometer-tracing/Observation API——会把 span 语义耦合进 Micrometer 注册表且倾向拦截式接入，与显式调用风格相悖，拒；OTel javaagent——黑盒插桩不可控、违背「自实现核心」气质，拒；实时开 span（context 传播）——需要跨 ReActLoop 层传句柄或 ThreadLocal 新机制，事后补记零侵入且时间戳同源审计，拒。

## R6. 门禁自动化与真机验收两档（环境实证驱动）

- **Decision**: 本机（WSL2）实测无 docker（Desktop 未开本 distro 集成）、无 kind/kubectl/helm。验收分两档：
  - **门禁档（无集群可全自动）**：`helm lint` + `helm template` 渲染 + `kubeconform` schema 校验 + 关键断言脚本（副本数/Secret 引用/探针路径/RWX accessMode/优雅参数），helm 与 kubeconform 二进制 curl 安装到用户目录；挂 `make helm-lint` + ci.yml 新 job（与 docker-build 同格：只验不推）。
  - **真机档（US1/US2/US4 走查）**：kind 集群（首选，需 docker）——实施到走查阶段先自装 kind/kubectl（用户目录）；docker 依赖按序尝试：请用户在 Docker Desktop 开启本 distro WSL integration（一次性用户操作）→ 不可行则 sudo 装 docker-ce → 再不可行 k3s 裸跑。真机档结果如实落卷，环境不可得时记录门禁档证据 + 待补项（沿 026/027 口径）。
- **Rationale**: 「能自动化的验证路径必须用尽」——lint/template/schema 三层无集群即可钉住 Chart 结构正确性；真机档环境有明确的自助获取阶梯。
- **Alternatives considered**: 跳过真机全走 template 断言——滚动升级零失败与 preStop 行为只有真集群能证，不可替代，拒。

## R7. US4 两笔留账的复测方法

- **Decision**:
  - **026 吞吐线性性**：mock provider 增加可选固定时延参数（`oryxos.providers[].mock-latency-ms`，默认 0=现状零变化）模拟真实 LLM 往返；发压端用独立进程（多进程 `hey`/自写并发脚本置于集群外），对 1 副本与 2 副本各压同负载取吞吐比。kind 单机多节点仍共享宿主 CPU——数字如实标注环境局限，目标 ≥1.6×。
  - **027 RWX 抽查**：kind 集群装 NFS provisioner（或 kind 多节点 + hostPath-RWX 等价物，如实记录卷类型），复跑 A 建 B 见/改盘 refresh 两条走查。
- **Rationale**: mock 时延把瓶颈从发压端移回并发承载面（修正 026 口径缺陷）；RWX 抽查同时兑现 027 SC-007 与本刀 US1 场景 3。
- **Alternatives considered**: 真 LLM 压测——费用与配额不可控且不可重复，拒。

## R8. 版本与发布集成

- **Decision**: Chart `appVersion` 跟随 pom 版本（Makefile 同款 awk 口径）；`version`（chart 自身）独立起 0.1.0。本刀不做 chart 仓库发布（helm push/OCI）——发行物先以 git 目录 + tar.gz 附件形式交付，release.yml 追加 chart 打包上传到 GitHub Release（与 tar.gz 并列，不新增基础设施）。`make helm-lint`/`make helm-package` 两个目标。
- **Rationale**: 与既有「tar.gz Release + GHCR 镜像」发布面最小增量拼接；OCI chart 仓库留待有真实分发需求。
- **Alternatives considered**: GHCR OCI helm push——多一条凭证/权限面，YAGNI 拒。

## R9. 零变化保障清单

- **Decision**: 明确不动面——Dockerfile/entrypoint/compose（除非探针联调发现必须的最小修正，须在 PR 声明）、`/api/v1/health` 语义、`bin/start.sh`、发行包结构；改动面全量列举——boot application.yml 两行（graceful + readiness 组）、ChannelAdminService.stopAll 释放租约、bin/stop.sh 等待时长、SpanRecorder 三调用点、mock provider 时延参数。单机档回归以全量测试 + 停机行为抽查覆盖。
