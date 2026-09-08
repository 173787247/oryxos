# Research: IM 入站渠道抽象与飞书双向接入（017）

**Date**: 2026-08-25 | **Spec**: [spec.md](./spec.md)

本文消解 plan 的全部技术未知项。每项 = Decision / Rationale / Alternatives considered。

---

## R1. 飞书接入 SDK：官方 oapi-sdk 2.8.5，用低层 `ws.Client + EventDispatcher`，不用高层 `LarkChannel`

**Decision**: 引入 `com.larksuite.oapi:oapi-sdk:2.8.5`（Maven Central，2026-08 最新）。长连接用低层 `com.lark.oapi.ws.Client`（WebSocket 管道 + 自动重连）+ `com.lark.oapi.event.EventDispatcher`（注册 `im.message.receive_v1` handler）；发送用 SDK 的 `im/v1/messages` 同步 API。**去重、@ 判断与剥离、群聊策略、分段发送全部自实现**，不用 SDK 2.7.x 新增的高层 `LarkChannel` 封装。

**Rationale**:
- 协议不可自研：飞书长连接是私有协议（HTTPS bootstrap 换 wss endpoint + protobuf 帧 `Pbbp2`），无公开规范，只能用官方 SDK。
- SDK 依赖干净：okhttp 是 vendored（`com.lark.oapi.okhttp` 包内），无 netty；唯一要留意的传递依赖是 guava 32.0.0-jre（需与项目现有依赖对齐检查）。JDK 1.8+ 编译，Java 21 无障碍。
- 低层而非 `LarkChannel` 的理由与宪法同构（「自实现核心，只借管道」）：FR-010 要求契约行为（去重、@ 剥离、分段、错误回复）由**参数化测试集钉死（飞书档 + 测试桩档）**——这些行为必须在我们自己的编排层实现才可测可复用；`LarkChannel` 把它们封进第三方黑盒，测试桩渠道无法共享同一套逻辑，且该 API 2.7.x 才出现、迭代快。
- 宪法 VII（禁异步编程模型）：`LarkChannel.connect()` 返回 `CompletableFuture`；低层 `ws.Client.start()` 是同步阻塞语义，配虚拟线程即可。
- `ws.Client` 自动重连默认开启（无限重试、间隔 120s+随机 30s、心跳 120s），断线恢复白拿。

**Alternatives considered**:
- `LarkChannel` 高层封装：功能吻合但控制权外移、契约不可测，弃。
- 自实现长连接协议：协议不公开，逆向维护成本无上限，弃。
- 入站 Webhook：Clarify-Q1 已裁决不做（需公网回调，违背内网部署定位）。

## R2. 长连接安全语义：免验签，凭证走 `${ENV}` 占位

**Decision**: 长连接模式下 `EventDispatcher.newBuilder("", "")` 两参数按官方要求填空串——通道自带加密与鉴权，无 encrypt key / 验签逻辑（FR-003 的「事件真实性校验」由 WebSocket 通道鉴权承担：非本应用凭证建立的连接收不到事件，SDK 层校验失败即拒绝）。`app_id`/`app_secret` 在 `.oryxos/channels.yaml` 里以 `${FEISHU_APP_ID}`/`${FEISHU_APP_SECRET}` 占位，解析复用与 `McpConfigLoader` 相同的正则口径。**缺失/未解析（值含 `${`）时必须点名报错、该渠道不上线**——仿 `ProvidersProperties.validate()` 的 `contains("${")` 检测，而非两个既有 loader 的 WARN 放行默认。

**Rationale**: 官方文档明确长连接「内置通信加密和鉴权，无需额外解密、验签」；SC-003 的「伪造事件 100% 拒绝」在长连接模式下由通道准入实现，拒绝痕迹 = 连接鉴权失败日志。FR-012/FR-013/SC-008 要求凭证不落明文 + 配置错误点名报错不带病上线。

**Alternatives considered**: encrypt key 验签（Webhook 模式产物，长连接不适用）；凭证进 `notify_channels` 表（该表只有 url 字段且明文落库，不符 FR-012）。

## R3. 事件去重：按 `message_id`，进程内有界 TTL 缓存

**Decision**: 去重键用 **`message_id`（不用 event_id）**，官方文档明确幂等应「使用 message_id 去重，不要依赖 event_id」。实现为编排服务内的进程内有界去重缓存（LinkedHashMap LRU，容量 5000 + TTL 12h，`synchronized` 保护），命中即静默丢弃。

**Rationale**: 低层 `ws.Client` 不做事件去重（其内部缓存只用于分片帧重组），SC-004 的 100% 去重需自建。本期单实例语义（spec Assumptions），进程内缓存即达标；重启窗口的重复已由 spec Edge Case 显式豁免（「已确认未完成的消息允许丢失，审计可见」——同理重启后缓存重建）。多副本共享去重属 v0.4 分布式底座。

**Alternatives considered**: SQLite `processed_events` 表（跨重启去重，但引入写放大与清理任务，超出单实例验收要求，YAGNI）；Guava Cache（项目未直接依赖 guava，不为此引入）。

## R4. 秒级确认与耗时推理解耦：handler 立即返回 + `AgentExecutionService.triggerAsync` 虚拟线程

**Decision**: 事件 handler 里只做「去重 + 归一化 + 提交」三步后立即返回（满足飞书 3 秒内处理完的要求，超时会触发平台重推）；ReAct 推理与回发在 `AgentExecutionService.triggerAsync(agentName, "feishu", sessionId, work)` 的虚拟线程里跑——完全复刻 `AgentApiController.trigger` 的既有骨架，顺带把 `agent_executions.source="feishu"` 审计白拿。「处理中」提示：提交任务时另起一个虚拟线程 `sleep(阈值)` 后检查完成标志（`CountDownLatch.await(timeout)`），未完成则先发一条「处理中，请稍候」。

**Rationale**: FR-008 硬性要求接收确认与回答发送解耦；宪法 VII 禁 CompletableFuture——两个虚拟线程 + CountDownLatch 是纯同步原语，合规。

**Alternatives considered**: handler 内同步跑完 ReAct（超 3 秒必触发重推，与 FR-004 去重对冲，弃）；定时轮询检查（多余复杂度）。

## R5. 会话与路由：私聊走 `process`，群聊走 `processStateless` 加渠道标签重载

**Decision**:
- **私聊**：`sessionManager.getOrCreate("feishu", <open_id>, <agentName>)` → `agentService.process(session, text)`。session_id 自动为 `feishu:<open_id>:<agent>`，历史窗口、并发锁、乐观并发、审计全部白拿，零 core 改动。
- **群聊**：为 `AgentService` 增加一个重载 `processStateless(String agentName, String userMessage, String executionTag)`（现方法硬编码 `"invoke-exec:"` 前缀，旧签名委托新重载保持兼容），群聊传 `"feishu-group"` 标签 → 审计里 session_id 为 `feishu-group:<UUID>`，满足 FR-014「渠道标识可区分可查询」（`session_id LIKE 'feishu%'`）。不建持久会话、不落 sessions 表——与 FR-006「群聊每次 @ 独立无状态、仅落审计」逐字一致。
- **@ 判断与剥离**：比对 `event.message.mentions[]` 里 mention 的 `open_id` 与机器人自身 open_id（应用启动时经 SDK 获取 bot 身份）；命中后把正文中 `@_user_N` 占位符按 mentions 表剥离/替换为人名。非 @ 群消息在归一化层直接丢弃（不进编排、不落任何记录，SC-002）。

**Rationale**: 探索确认 `JpaSessionManager.sessionId()` 是全库唯一拼接点、channel 为自由字符串零 DDL 改动；`processStateless` 已有但前缀不可定制，最小重载即补齐审计渠道维度。`llm_calls`/`tool_invocations` 不加 channel 列——SC-006 只要求「字段与 CLI/REST 渠道同构」，前缀反推与既有渠道口径一致。

**Alternatives considered**: 群聊也建持久会话（Clarify-Q3 已裁决弃——串扰与并发写冲突）；审计表加 channel 列（超出 SC 要求，且 SQLite ALTER 成本，留给后续审计特性）。

## R6. 配置与热更：`.oryxos/channels.yaml` + AdminService（复刻 MCP 三件套），REST `/api/v1/channels`

**Decision**: 新增 `.oryxos/channels.yaml`（顶层 `channels:` 列表；字段 name/type/app_id/app_secret/agent/enabled）。加载器复刻 `McpConfigLoader` 全套口径：`load()`（resolved）/`loadRaw()`（字面量，供 CRUD 回写不泄密）双读法 + `save()` 落盘收紧 `rw-------`。热更复刻 `McpServerAdminService`：`synchronized` 的 add/update/remove = 校验 → 落盘 → **先 disconnect 再 connect**，无需重启（FR-013）。REST 仿 `McpApiController`：`/api/v1/channels` CRUD + `GET /api/v1/channels/status`（渠道在线状态，FR-014）。注册表暴露**活视图**不 `Map.copyOf` 拍照（#203 教训）。管理台前端页面不在本期（API 先行）。

**Rationale**: 探索确认 `config/application.yml`（Spring `@ConfigurationProperties`）无任何热更机制，走它必违反 FR-013；MCP admin 是仓内唯一「落盘 + 立即生效」先例且形态完全同构（长连接客户端 ≈ MCP client 连接）。绑定放 channels.yaml 而非 AGENT.md frontmatter：绑定方向是「飞书应用 → Agent」（一对一，Clarify-Q2），归渠道配置；`Profile.channels` 字段现状无人消费，不启用避免双真相源（宪法 IV 精神）。

**Alternatives considered**: application.yml + 重启生效（违反 FR-013）；AGENT.md frontmatter 声明渠道（方向反了，且造成 Agent 目录与渠道凭证耦合）；数据库表存配置（凭证明文落库违反 FR-012）。

## R7. 出站与沙箱：发送前显式 `sandbox.enforce(HTTP_REQUEST)`，补 `config/application.yml` 白名单

**Decision**: 飞书回复经 SDK 发送前，显式调用 `sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, apiUrl))` 校验域名；拒绝时沿用 `SandboxViolationException` 的既有引导文案口吻。同时把 `*.feishu.cn` 补进 `config/application.yml` 的 `http.allowed_domains`（jar 内默认与 example 已有，唯独实际运行的外部配置缺失）。

**Rationale**: 探索确认 `WhitelistSandbox` 是显式调用式而非全局拦截——渠道模块自己的 HTTP 默认完全绕过沙箱；spec「复用沙箱域名白名单」的承诺必须靠主动接线兑现。`HTTP_REQUEST` 白名单是 deny-all 语义，域名缺失会导致发送全拒。

**Alternatives considered**: 不接沙箱（spec 承诺落空）；拦截 SDK 内部 httpclient（vendored 依赖，不可注入，弃）。

## R8. 消息边界：150KB 上限、按配置分段、非文本回能力说明

**Decision**: 文本消息请求体上限 150KB（官方，错误码 230025）；按字符数分段（默认 4000 字符/段，可配置，给 JSON 转义留余量），循环顺序发送不丢内容（FR-009）。发送带 `uuid` 幂等参数（≤50 字符）防重复投递。非文本消息（`message_type != "text"`）在归一化层识别，回复「当前仅支持文本提问」（FR-009）；回复群聊使用 `im/v1/messages/:message_id/reply` 引用原消息（US2 AS-1「与提问可对应」），私聊直发。频控注意：同一用户/群 5 QPS，本期串行处理天然低于限额，不做限流器。

**Rationale**: 全部为官方文档核实值；引用回复是平台原生的「问答对应」机制，零自建成本。

**Alternatives considered**: 按 UTF-8 字节精确切 150KB（复杂且贴上限有风险，保守字符数更稳）；富文本/卡片回复（30KB 上限且属 v0.3 HITL 卡片范围）。

## R9. 渠道契约的形态：契约放 `oryxos-core`，编排服务共享，适配器只做协议转换

**Decision**: `oryxos-core` 新增 `io.oryxos.core.channel` 包承载入站渠道契约（依赖倒置，宪法架构约束）：
- 值对象：`InboundMessage`（渠道类型、应用标识、message_id、会话类型 P2P/GROUP、用户/群标识、正文、是否 @ 机器人、原消息标识）
- 适配器接口：`InboundChannelAdapter`（start/stop/状态/回复发送）——每渠道实现，只做「平台协议 ↔ 归一化模型」转换
- 编排服务：`InboundMessageService`——去重、绑定路由、Agent 存在校验、私聊/群聊分流、错误与能力说明回复、「处理中」提示、审计提交。**契约行为全部在此层，参数化契约测试集（飞书档 + 测试桩档）直接对其钉死（SC-007）**
- 注册与状态：`InboundChannelRegistry` + `ChannelStatus`

新模块 `oryxos-channel-feishu` 与 `oryxos-channel-cli` 平级，仅依赖 `oryxos-core` + oapi-sdk；类保持纯 POJO 无 Spring 注解，在 `OryxOsRuntime` 显式 `@Bean` 装配（含 `initMethod`/`destroyMethod` 管长连接生命周期）——与 CliChannel/WorkspaceWatcher/AgentScheduler 既有风格一致。

**Rationale**: FR-010 要求「新增 IM 渠道只加适配器、核心模块零修改」——语义（去重、路由、会话、审计）必须收敛在 core 的共享编排层，适配器越薄扩展性越强；测试桩渠道实现同一接口即可跑通同一契约测试集。仓内探索确认无任何既有 Channel 抽象，需从零建。

**Alternatives considered**: 契约放渠道模块（下游持有契约违反依赖倒置）；每渠道自带编排（逻辑复制，测试集无法参数化）；`@Component` 扫描装配（渠道模块沾 Spring 依赖，与 channel 模块纯 POJO 风格不一致）。

## R10. 一进程多应用与 Agent 可用性

**Decision**: 每个飞书应用一个 `ws.Client` 实例（一 client 一连接），多应用即多实例并存，互相独立（官方上限：单应用 50 条连接，远超需求）。「绑定的 Agent 被停用/删除」判定 = `profileRegistry.get(name).isEmpty()`（探索确认底座无 Agent 停用态，只有存在/不存在两态）；不存在时回复「Agent 不可用」并照常落审计（Edge Case）。

**Rationale**: 与 Clarify-Q2「一应用一 Agent，多 Agent 即多应用」一一对应；同应用多实例时飞书随机投递不广播，单实例部署无感。

**Alternatives considered**: 无。
