# Feature Specification: Provider 失败切换与业务指标导出

**Feature Branch**: `023-provider-fallback`

**Created**: 2026-09-02

**Status**: Draft

**Input**: User description: "Provider 失败切换与业务指标导出（023-provider-fallback）：v0.3 治理面收官刀——LLM Provider 单点故障不再打断 Agent 服务，运维可在既有监控栈里看到调用健康度。AGENT.md provider 节新增 fallback 声明（有序列表 name+model，引用已注册 Provider）；单次 LLM 调用的 provider 侧故障按序切换重试，全部失败才上抛；不可重试的业务性失败不切换；max_iterations 口径不变。审计每次尝试各写一条 llm_calls，021 trace 时间线主备尝试同链可见；切换 WARN 日志带 traceId。流式：首 token 前失败可切换，token 已流出按 019 error 语义收尾。零声明=现状零变化。业务指标：既有 Prometheus 端点新增 LLM 调用/token/工具调用/策略拦截/fallback 切换等业务指标（oryxos_ 前缀），指标与审计正交。不做：智能路由、断路器、跨请求健康记忆。"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Provider 故障时服务不中断 (Priority: P1)

Agent 作者在 Agent 配置的 provider 节声明一个或多个备用 Provider（有序列表，各自带模型名）。主 Provider 发生故障（网络不通、超时、服务端错误、限流、密钥失效等）时，该次调用自动按声明顺序换备用 Provider 重发同一请求，Agent 的对话/定时任务/IM 问答照常完成——终端用户无感知。只有全部候选都失败时，才以最后一次的错误按现状口径反馈。未声明 fallback 的 Agent 行为与现在完全一致。

**Why this priority**: 这是本特性的存在理由——LLM Provider 是 Agent 唯一的外部强依赖，单点故障直接打断所有会话与定时任务；企业场景的可用性诉求排第一。

**Independent Test**: 给 Agent 声明主（必然失败的 Provider）+ 备（可用的 Provider）；发起对话——回复正常返回；查审计可见失败主尝试与成功备尝试各一条记录。

**Acceptance Scenarios**:

1. **Given** Agent 声明了主 Provider（故障）与备 Provider（正常）, **When** 发起一次对话, **Then** 对话正常完成，回复来自备 Provider，调用方无感知
2. **Given** 声明的全部候选 Provider 都故障, **When** 发起对话, **Then** 以最后一次尝试的错误按现状口径反馈（不无限重试、不吞错）
3. **Given** Agent 未声明任何 fallback, **When** 主 Provider 故障, **Then** 行为与本特性交付前完全一致（回归零破坏）
4. **Given** 失败属于不可重试的业务性错误（如请求内容本身非法）, **When** 该次调用失败, **Then** 不切换备用、直接按现状反馈（换 Provider 无意义的错误不浪费尝试）
5. **Given** 流式对话且首个内容片段尚未送出, **When** 主 Provider 故障, **Then** 切换备用后流式照常，客户端无感知；**Given** 内容已开始流出后故障, **Then** 不切换、按既有流中错误语义收尾（避免重复输出）

---

### User Story 2 - 切换过程可回放可追责 (Priority: P2)

管理员事后核查一次"用户没感觉但其实切换过"的调用：审计里主备每次尝试各有一条记录（Provider 名如实、失败原因在失败记录上）；用该轮 trace ID 查时间线，主备尝试同链按时间序可见；服务日志有一条切换警告（从谁切到谁），凭 trace ID 与审计互查。运维据此发现"主 Provider 最近老在切"并提前处置。

**Why this priority**: 自动切换若不可回放，就是把故障藏起来——治理面版本的底线是"任何自动行为都留痕"；且 021 刚交付的 trace 能力天然承载它。

**Independent Test**: 触发一次主败备成的调用 → 审计查到两条记录（失败主 + 成功备）、trace 时间线两个 LLM 步同链、日志有带 trace 的切换警告。

**Acceptance Scenarios**:

1. **Given** 一次主败备成的调用, **When** 查审计记录, **Then** 失败主尝试与成功备尝试各一条，Provider 名与成败如实
2. **Given** 同一轮处理, **When** 按 trace ID 查时间线, **Then** 两次 LLM 尝试同链按时间序可见（切换过程可回放）
3. **Given** 切换发生, **When** 检索服务日志, **Then** 有一条切换警告（来源与目标 Provider），且与该轮 trace ID 可互查

---

### User Story 3 - 监控栈里可见调用健康度 (Priority: P3)

运维把系统既有的监控端点接入企业 Prometheus/Grafana：无需登录管理台，就能在监控栈里看到 LLM 调用量与耗时（按 Provider/模型/成败）、token 消耗、工具调用量（按工具/成败）、策略拦截次数、fallback 切换次数，并据此配置告警（如"切换次数突增"）。指标只做监控聚合，不改变审计落库口径。

**Why this priority**: 补 v0.3 看板最后一角——016 报表覆盖"人看"，这里覆盖"机器看+告警"；排 P3 因为端点与基础指标已在，本故事是业务指标增量。

**Independent Test**: 触发若干次对话（含策略拦截与 fallback 场景）→ 抓取监控端点文本 → 各业务指标存在且计数与实际行为一致。

**Acceptance Scenarios**:

1. **Given** 系统处理过若干 LLM 调用与工具调用, **When** 抓取监控端点, **Then** 调用计数/耗时/token 指标按 Provider/模型/工具/成败维度呈现且数值与实际一致
2. **Given** 发生过策略拦截与 fallback 切换, **When** 抓取监控端点, **Then** 拦截计数与切换计数如实累加
3. **Given** 指标采集开启, **When** 对照审计表, **Then** 审计写入口径与数量不受影响（指标与审计正交）

---

### Edge Cases

- 声明的备用 Provider 未注册或已被删除：该候选跳过并留痕（日志警告），继续尝试下一个；全部无效等同"无可用候选"
- 备用 Provider 自身也需要凭证：沿用其注册表配置（022 已加密存储），与主 Provider 同一取用路径
- 主备声明成同一个 Provider（配置冗余）：照常按序尝试，不做去重魔法（声明什么执行什么）
- 工具调用轮次中途切换：同一轮 ReAct 内后续调用从主 Provider 重新开始尝试（每次调用独立判断，无跨调用记忆）
- 切换后回复风格/模型能力差异：如实使用声明的备用模型，不做能力对齐（作者声明即授权）
- 指标端点在未发生任何调用时：业务指标不存在或为零值，不报错

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Agent 配置的 provider 节 MUST 支持声明有序备用列表（每项含 Provider 名与模型名，引用已注册 Provider）；未声明时行为与现状完全一致
- **FR-002**: 单次 LLM 调用发生 Provider 侧故障（网络错误/超时/服务端 5xx/限流/认证失败）时，系统 MUST 按声明顺序逐个尝试备用 Provider 重发同一请求，直到成功或候选耗尽；候选耗尽时 MUST 以最后一次错误按现状口径上抛
- **FR-003**: 不可重试的业务性失败（请求内容本身非法等换 Provider 无意义的错误）MUST NOT 触发切换
- **FR-004**: 切换语义 MUST 限定在"单次 LLM 调用"层面：ReAct 轮次数（max_iterations）口径不变，每次调用独立从主 Provider 开始尝试（无跨调用健康记忆）
- **FR-005**: 每次尝试（失败与成功）MUST 各写一条 LLM 审计记录，Provider/模型/成败/错误如实；同轮尝试共享该轮 trace，时间线可回放切换过程
- **FR-006**: 切换发生时 MUST 记一条警告日志（来源 Provider → 目标 Provider），处理路径日志携带该轮 trace 标识（021 MDC 机制）
- **FR-007**: 流式调用 MUST 遵守边界：首个内容片段尚未送出前的失败可切换（客户端无感知）；内容已开始流出后 MUST NOT 切换，按既有流中错误语义收尾
- **FR-008**: 声明中引用了未注册/已删除 Provider 的候选项 MUST 跳过并留告警日志，不中断尝试序列
- **FR-009**: 系统 MUST 在既有监控端点上暴露业务指标：LLM 调用计数与耗时（按 Provider/模型/成败）、token 消耗计数、工具调用计数（按工具/成败）、策略拦截计数、fallback 切换计数（按来源/目标）；指标命名遵循业界惯例并带统一前缀
- **FR-010**: 指标采集 MUST 不改变审计落库口径与数量（指标供监控聚合，审计供精确回放，二者正交）；指标采集失败 MUST NOT 影响主链路
- **FR-011**: 本特性 MUST NOT 包含：智能路由（按成本/延迟自动选路）、断路器/半开状态机、跨请求的 Provider 健康记忆、管理台新页面

### Key Entities

- **备用声明（fallback 列表）**: Agent 配置中 provider 节的有序候选清单，每项为已注册 Provider 的名字 + 该 Provider 下使用的模型名；声明即授权（作者对备用模型的能力差异负责）
- **一次调用的尝试序列**: 主 Provider + 按序备用构成的执行序列；每个尝试产生一条审计记录，全序列共享该轮 trace
- **业务指标**: 监控端点上的聚合计数/耗时序列，维度含 Provider/模型/工具/成败/切换来源与目标；与审计记录正交

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 主 Provider 故障、备用可用的场景下，对话/定时任务/流式（首片段前）100% 正常完成，终端用户无感知
- **SC-002**: 未声明 fallback 的 Agent 在本特性交付前后行为完全一致（回归零破坏，含错误反馈口径）
- **SC-003**: 每次主败备成的调用在审计中恰有两条记录、trace 时间线同链可见、日志有带 trace 的切换警告（切换过程 100% 可回放）
- **SC-004**: 不可重试失败与流中失败 0 次触发切换（边界零越界）
- **SC-005**: 监控端点上五类业务指标（LLM 调用/token/工具调用/策略拦截/切换）与实际行为计数一致（抽样对照零偏差）
- **SC-006**: 指标采集开启前后审计表写入数量与内容口径零变化
- **SC-007**: 全部候选失败时错误反馈时延不超过「候选数 × 单次调用超时」量级（不引入额外等待/退避）

## Assumptions

- 备用 Provider 的注册与凭证管理沿用既有 Provider 注册表（022 加密存储），本特性不新增凭证面
- 「Provider 侧故障」的具体错误分类在设计阶段结合既有调用栈的异常形态定稿；分类原则：换一个 Provider 有合理成功预期的才算可切换
- 流式「首个内容片段」以对客户端的首次内容写出为界（心跳注释不算内容）
- 监控端点与基础运行指标已存在（此前版本预支），本特性只做业务指标增量
- 智能路由所需的数据积累（延迟/成本/成功率的历史序列）恰由本特性的指标与审计提供——为 roadmap 后续项铺路但不实现
