# Tasks: IM 入站渠道抽象与飞书双向接入

**Input**: Design documents from `/specs/017-feishu-im-channel/`

**Prerequisites**: plan.md、spec.md、research.md（R1~R10）、data-model.md、contracts/（inbound-channel-contract.md 行为规则 B1~B10/A1~A4、channels-api.md）、quickstart.md（V1~V7）

**Tests**: 包含——FR-010/SC-007 要求契约行为由参数化测试集钉死（飞书档 + 测试桩档），测试是本特性的交付物之一；另按宪法质量门禁，`mvn verify` 必须全绿。

**Organization**: 按 User Story 分阶段；US1 私聊闭环为 MVP。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1 私聊闭环 / US2 群聊 @ / US3 管理员配置 / US4 渠道抽象

## Path Conventions

Maven 多模块：契约与编排在 `oryxos-core`，适配器在新模块 `oryxos-channel-feishu`，REST 在 `oryxos-web`，装配在 `oryxos-cli`（`OryxOsRuntime`）。零 SQLite schema 变更。

---

## Phase 1: Setup（模块骨架与依赖）

**Purpose**: 新模块挂载、SDK 引入、白名单与示例配置就位

- [x] T001 新建 `oryxos-channel-feishu/pom.xml`（parent 指根 pom；依赖 `io.oryxos:oryxos-core` + `com.larksuite.oapi:oapi-sdk:2.8.5`）；根 `pom.xml` 的 `<modules>` 与 `<dependencyManagement>` 各加一条（紧跟 oryxos-channel-cli，参照其写法）；跑 `mvn -q dependency:tree -pl oryxos-channel-feishu` 确认 guava 32.0.0-jre 无版本冲突（R1）
- [x] T002 `oryxos-cli/pom.xml` 与 `oryxos-boot/pom.xml` 各加 `io.oryxos:oryxos-channel-feishu` 依赖（Runtime 装配 import 与 fat jar 打包都需要）；`mvn -q compile -pl oryxos-cli -am` 通过
- [x] T003 [P] `config/application.yml` 的 `http.allowed_domains` 补 `*.feishu.cn`（实际运行配置当前缺失，R7）；新增 `config/channels.yaml.example`（按 data-model.md §3 的 YAML 样例，凭证用 `${FEISHU_APP_ID}`/`${FEISHU_APP_SECRET}` 占位）

**Checkpoint**: `mvn -q compile` 全模块通过，新模块空壳在构建里

---

## Phase 2: Foundational（core 渠道契约包——阻塞所有故事）

**Purpose**: `io.oryxos.core.channel` 契约与共享编排，全部故事的公共地基

**⚠️ CRITICAL**: 本阶段完成前不得开始任何 User Story

- [x] T004 [P] 值对象与接口：`oryxos-core/src/main/java/io/oryxos/core/channel/` 下创建 `InboundMessage.java`（record，字段按 data-model.md §1 含不变式注释）、`ChatKind.java`（P2P/GROUP）、`ChannelStatus.java`（record + state 枚举 CONNECTED/DISCONNECTED/DISABLED/ERROR）、`InboundChannelAdapter.java`（name/type/start/stop/status/sendReply，按 contracts/inbound-channel-contract.md 签名）——纯 POJO 零框架依赖
- [x] T005 [P] 配置模型与加载器：同包创建 `ChannelConfig.java`（name/type/appId/appSecret/agent/enabled）与 `ChannelConfigLoader.java`——复刻 `McpConfigLoader` 口径：`load()`（resolved）/`loadRaw()`（保留 `${}` 字面量）/`save()`（落盘 `.oryxos/channels.yaml` 并 `restrictToOwner` 收紧 `rw-------`）；`${ENV}` 正则与既有两 loader 完全一致（`\$\{([A-Za-z0-9_]+)}`）；校验按 data-model.md §3 逐条点名报错（name 唯一/type 已注册/凭证 resolved 后不含 `${`）；单测 `oryxos-core/src/test/java/io/oryxos/core/channel/ChannelConfigLoaderTest.java` 覆盖双读法、占位保留落盘、三类校验报错文案
- [x] T006 [P] 去重器：同包创建 `MessageDeduplicator.java`（`LinkedHashMap` LRU 容量 5000 + TTL 12h，`synchronized`，原子 `markIfFirst(channelName + ":" + messageId)`，R3）；单测 `MessageDeduplicatorTest.java` 覆盖重复拦截、LRU 淘汰、TTL 过期
- [x] T007 注册表：同包创建 `InboundChannelRegistry.java`（`ConcurrentHashMap<String, InboundChannelAdapter>`，`statusAll()` 返回**活视图**不 `Map.copyOf` 拍照——#203 教训，参照 `ToolRegistry.asMap()` 写法）
- [x] T008 `AgentService` 重载：`oryxos-core/src/main/java/io/oryxos/core/agent/AgentService.java` 新增 `processStateless(String agentName, String userMessage, String executionTag)`（session_id = `executionTag + ":" + UUID`），旧签名 `processStateless(name, msg)` 委托新重载传 `"invoke-exec"` 保持行为不变（R5）；补/改 `oryxos-core` 既有 AgentService 测试断言前缀
- [x] T009 共享编排服务：同包创建 `InboundMessageService.java`，实现 contracts 行为规则 B1~B10 全部：`onMessage(msg, replyVia)` 在确认线程内只做去重（B1）+ `agentExecutionService.triggerAsync(agent, "feishu", sessionId, work)` 提交后返回（B5）；虚拟线程内按 `ChatKind` 分流——P2P 走 `sessionManager.getOrCreate(msg.channelType(), msg.userId(), agent)` + `agentService.process`（B2），GROUP 走 `processStateless(agent, content, msg.channelType() + "-group")`（B3）；回复经 `replyVia.sendReply(chatId, text, GROUP ? messageId : null)`（B4）；非文本回能力说明（B7）；Agent 不存在回「Agent 不可用」（B9，`profileRegistry.get(agent).isEmpty()` 判定）；处理失败回可读说明不含堆栈（B6）；「处理中」提示——提交时另起虚拟线程 `CountDownLatch.await(阈值默认 15s 可配)` 未完成先发提示（B8，纯同步原语，禁 CompletableFuture）；构造注入：`AgentService`/`SessionManager`/`ProfileRegistry`/`AgentExecutionService`/`MessageDeduplicator`
- [x] T010 编排服务单测：`oryxos-core/src/test/java/io/oryxos/core/channel/InboundMessageServiceTest.java`（Mockito），逐条覆盖 B1~B10（重复丢弃、私聊三元组、群聊 tag、失败文案、处理中提示时序用短阈值）

**Checkpoint**: `mvn -q test -pl oryxos-core` 全绿——契约地基就绪，US1/US2/US3/US4 可并行开工

---

## Phase 3: User Story 1 - 飞书私聊问答闭环（P1）🎯 MVP

**Goal**: 员工私聊提问→Agent 完整推理→同会话收到回答，多轮承接上下文；失败有可读说明

**Independent Test**: quickstart V1——私聊问答 + 追问承接 + `sessions` 表落 `channel=feishu`

- [x] T011 [P] [US1] 事件归一化（私聊部分）：`oryxos-channel-feishu/src/main/java/io/oryxos/channel/feishu/FeishuEventNormalizer.java`——`P2MessageReceiveV1` 事件 → `InboundMessage`：解析 chat_type=p2p、message_id、sender open_id、chat_id；`message_type=="text"` 解析 content JSON 取 text，非文本置 `textual=false`（A1 群聊部分留 US2）；单测 `FeishuEventNormalizerTest.java` 用真实事件 JSON 样例覆盖文本/图片/空文本
- [x] T012 [P] [US1] 消息发送器：`oryxos-channel-feishu/src/main/java/io/oryxos/channel/feishu/FeishuMessageSender.java`——经 SDK `Client`（appId/appSecret 构建，tenant token 自动管理）调 `im/v1/messages` 同步发送；发送前 `sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://open.feishu.cn/..."))`（A3/R7，拒绝时沿用 `SandboxViolationException` 引导文案口吻）；超长按可配段长（默认 4000 字符）分段顺序发送（A3/R8）；每段带随机 `uuid`（≤50 字符）幂等；`replyToMessageId` 非空走 `im/v1/messages/:message_id/reply`；单测 `FeishuMessageSenderTest.java` 覆盖分段边界、sandbox 拒绝、reply 分支（HTTP 层 mock）
- [x] T013 [US1] 适配器：`oryxos-channel-feishu/src/main/java/io/oryxos/channel/feishu/FeishuChannelAdapter.java` 实现 `InboundChannelAdapter`——`start()`：`EventDispatcher.newBuilder("", "")`（长连接免验签，两参必空串，R2）注册 `onP2MessageReceiveV1` → normalizer → `inboundMessageService.onMessage(msg, this)`；`ws.Client.Builder(appId, appSecret)` 建连（自动重连白拿）；启动时经 SDK 获取 bot 身份（open_id，供 US2 @ 判断）；`stop()` 幂等断开；`status()` 实时状态；`start` 前置校验凭证 resolved + 绑定 Agent 存在，失败抛点名异常（A4）；另建空 marker `ChannelFeishuModule.java`
- [x] T014 [US1] Runtime 装配：`oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java` 新增显式 `@Bean`（紧挨 `cliChannel`）：`ChannelConfigLoader`、`MessageDeduplicator`、`InboundChannelRegistry`、`InboundMessageService`、渠道启动器 Bean（`initMethod`/`destroyMethod` 管生命周期，参照 `WorkspaceWatcher` 先例）——启动时 `loader.load()` 逐条校验并 `new FeishuChannelAdapter(...).start()` 注册进 registry；单条失败记 ERROR + registry 留 ERROR 状态、不阻断其余启动（FR-013 语义，仿 `ProfileLoader.loadAll` 口径）
- [x] T015 [US1] ✅（2026-08-25 真机通过）端到端验证：按 quickstart 前置条件配真实飞书自建应用，跑 V1（私聊问答 + 追问承接 + sessions 审计核查）与 V3 前半（慢问题仅 1 条回答）；修复发现的问题并把样例事件 JSON 回填 T011 测试

**Checkpoint**: MVP 可演示——飞书私聊完整闭环，审计落库

---

## Phase 4: User Story 2 - 群聊 @ 独立问答（P2）

**Goal**: 群里 @ 机器人得到引用原消息的独立回答；非 @ 消息零响应零留痕；多人互不串扰

**Independent Test**: quickstart V2——@ 响应且互相独立、非 @ 零留痕

- [x] T016 [P] [US2] 归一化群聊部分：扩展 `FeishuEventNormalizer.java`——chat_type=group 时解析 `mentions[]`，以 mention 的 `open_id` 与 bot 自身 open_id 比对判定 `mentionedBot`（R5）；剥离 @ 机器人占位符 `@_user_N`、其余 mention 替换为人名（FR-002/A2）；**非 @ 群消息返回空（丢弃标记），不构造 InboundMessage**（A1/SC-002）；扩展 `FeishuEventNormalizerTest.java`：@bot 剥离、@他人替换、非 @ 丢弃、@bot 加多 mention 混合
- [x] T017 [US2] 适配器群聊接线：`FeishuChannelAdapter` 的 handler 对 normalizer 返回的丢弃标记直接 return（不进编排、不留痕）；群聊消息进编排后回复自动带 `replyToMessageId`（B4 已在 T009 实现，此处验证接线）；补集成式单测：群聊事件 → `processStateless` 被调且 tag 为 `feishu-group`、`sessions` 无写入
- [x] T018 [US2] ✅（2026-08-25 真机通过）端到端验证：跑 quickstart V2（@ 响应引用原消息、两人互不串扰、非 @ 零留痕核查 `sessions`/`agent_executions`）与 V7 非文本场景；修复问题

**Checkpoint**: US1+US2 均独立可测，群聊语义与 Clarify-Q3 一致

---

## Phase 5: User Story 3 - 管理员配置接入（P2）

**Goal**: 凭证走环境变量、绑定配置驱动；变更免重启生效；三类配置错误点名报错不带病上线

**Independent Test**: quickstart V4（缺凭证/Agent 不存在两类点名报错）+ V5（热更换绑定即生效）

- [x] T019 [US3] AdminService：`oryxos-core/src/main/java/io/oryxos/core/channel/ChannelAdminService.java`——复刻 `McpServerAdminService` 骨架：`synchronized` 的 `add`/`update`/`remove`/`reload` = 校验（复用 T005 校验）→ `save()` 落盘 → **先 `stop()` 旧适配器再 `start()` 新配置**（避免新旧连接并存）→ 更新 registry；适配器创建经 `Map<String, Function<ChannelConfig, InboundChannelAdapter>>` 类型工厂（feishu 工厂在 Runtime 装配注入，core 不依赖飞书模块）；单测 `ChannelAdminServiceTest.java` 覆盖断旧建新顺序、校验失败不落盘、name 冲突
- [x] T020 [P] [US3] REST 面：`oryxos-web/src/main/java/io/oryxos/web/controller/ChannelApiController.java` + `dto/ChannelView.java`/`ChannelStatusView.java`/`ChannelRequest.java`——按 contracts/channels-api.md 实现 5 端点（列表用 `loadRaw()` 且 appSecret 掩码、status 走 registry 活视图、写操作走 AdminService、错误映射 400/404 统一 `ApiResponse`，参照 `McpApiController`）；MockMvc 测试 `ChannelApiControllerTest.java` 覆盖 CRUD、掩码不泄密、点名错误文案
- [x] T021 [US3] 启动校验闭环（SC-008 三类 100%）：确认/补齐三类错误路径的点名文案与「不带病上线且不影响其余功能」行为——缺凭证（`app_secret 未配置或环境变量未解析，请检查 FEISHU_APP_SECRET` 口径）、Agent 不存在、绑定格式非法（YAML 结构/name 非法/type 不支持）；每类各一条集成测试（T014 启动器路径 + T019 变更路径双入口）
- [x] T022 [US3] ✅（2026-08-25 真机通过）端到端验证：跑 quickstart V4（两类报错 + status 呈现 ERROR 与原因）与 V5（运行中 PUT 换绑 Agent 即生效）；修复问题

**Checkpoint**: 配置接入企业可落地，FR-012/FR-013/SC-008 达成

---

## Phase 6: User Story 4 - 可复用的入站渠道抽象（P3）

**Goal**: 契约行为由参数化测试集钉死；测试桩渠道零 core 修改跑通全链路

**Independent Test**: quickstart V6——契约测试两档全绿 + 桩渠道接入 `oryxos-core` 零 diff

- [x] T023 [P] [US4] 测试桩渠道：`oryxos-core/src/test/java/io/oryxos/core/channel/StubChannelAdapter.java`——实现 `InboundChannelAdapter`，内存收发（发出的回复存 List 供断言），可注入模拟消息
- [x] T024 [US4] 参数化契约测试集：`oryxos-core/src/test/java/io/oryxos/core/channel/InboundMessageServiceContractTest.java`——JUnit 5 `@ParameterizedTest` 对 contracts B1~B10 逐条断言（桩档参数源）；设计为可复用测试基类（`abstract` 基类 + 桩子类），供飞书档继承
- [x] T025 [US4] 契约测试飞书档：`oryxos-channel-feishu/src/test/java/io/oryxos/channel/feishu/FeishuChannelContractTest.java`——继承 T024 基类，参数源换 `FeishuEventNormalizer` 真实事件 JSON 归一化产出 + mock 发送端；两档全绿
- [x] T026 [US4] SC-007 证据固化：执行 `git diff --stat <Phase 2 完成提交> -- oryxos-core/` 确认桩渠道接入（T023~T024）未改动 `oryxos-core/src/main`；把验证命令与结论记入 `specs/017-feishu-im-channel/quickstart.md` V6 结果

**Checkpoint**: 渠道契约资产成立，企微/钉钉接入路径被测试钉死

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T027 [P] 文档同步（宪法模块演进要求）：`CLAUDE.md` 模块表加 `oryxos-channel-feishu` 行；`docs/TechnicalSolution.md` §10 同步模块清单与渠道契约说明
- [x] T028 [P] 日志与安全审查：渠道全链路结构化日志（连接建立/断开/重连、事件拒绝痕迹、点名报错）不含 app_secret 与用户消息明文之外的敏感信息；错误回复不泄堆栈（B6 复查）；`channels.yaml` 权限 `rw-------` 复查
- [x] T029 质量门禁：`mvn -q verify` 全绿（Spotless/P3C/Checkstyle/SpotBugs/OWASP Dependency-Check——重点看新增 oapi-sdk 及其 guava/httpclient 传递依赖有无高危 CVE，有则评估升级或抑制并记录理由）
- [x] T030 ✅（V1~V7 全部通过，结果固化于 quickstart.md）验收收尾：完整跑 quickstart V1~V7 并逐条对照 spec SC-001~SC-009 记录结果；遗留项记入 spec 或另立 issue

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 Setup**: 无依赖
- **Phase 2 Foundational**: 依赖 Phase 1；**阻塞全部 US**
- **Phase 3 US1（P1）**: 依赖 Phase 2 —— MVP
- **Phase 4 US2（P2）**: 依赖 Phase 2；T016 扩展 T011 的文件（与 US1 有文件级先后）
- **Phase 5 US3（P2）**: 依赖 Phase 2；T019 依赖 T013 存在适配器可断建（工厂注入在 T014）
- **Phase 6 US4（P3）**: 依赖 Phase 2（T023/T024 只依赖 core）；T025 依赖 T011/T016 归一化完成
- **Phase 7 Polish**: 依赖全部所需 US 完成

### User Story Dependencies

- US1：仅依赖 Foundational——独立可测（quickstart V1）
- US2：不依赖 US1 的逻辑，但 T016 修改 US1 建的 Normalizer 文件，建议顺序执行
- US3：AdminService/REST 独立于 US1/US2 逻辑；端到端验证需 US1 的适配器在位
- US4：桩档（T023/T024）完全独立，可与 US1 并行；飞书档（T025）需归一化完成

### Parallel Opportunities

- Phase 2 内：T004/T005/T006 三条并行（不同文件）；T007/T008 随后并行
- Phase 3 内：T011/T012 并行（归一化与发送器互不依赖）
- 跨故事：Phase 2 完成后，US4 的 T023/T024（core test）可与 US1 的 T011~T013（feishu main）并行
- Phase 7：T027/T028 并行

## Parallel Example: Phase 2

```bash
# Foundational 三条并行开工：
Task: "T004 值对象与接口 io/oryxos/core/channel/*.java"
Task: "T005 ChannelConfig + ChannelConfigLoader + 单测"
Task: "T006 MessageDeduplicator + 单测"
```

## Implementation Strategy

### MVP First（US1 Only）

1. Phase 1 → Phase 2（地基）→ Phase 3（US1 私聊闭环）
2. **停下验证**：quickstart V1 真实飞书应用跑通
3. 可演示：员工私聊 Agent 完整问答 + 审计落库

### Incremental Delivery

1. + US2 → 群聊 @ 独立问答（V2）
2. + US3 → 企业可落地的配置与热更（V4/V5）
3. + US4 → 契约资产钉死（V6）
4. Polish → `mvn verify` 全绿 + SC 全量对照

### 实现纪律（贴宪法）

- 渠道模块与 core 契约类保持纯 POJO，装配只在 `OryxOsRuntime`
- 全程同步 + 虚拟线程；出现 `CompletableFuture` 即违宪（SDK 内部除外，不外溢到我们代码）
- 每完成一个任务或逻辑组提交一次；提交信息遵循项目规范
