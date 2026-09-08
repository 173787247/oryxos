# Implementation Plan: IM 入站渠道抽象与飞书双向接入

**Branch**: `017-feishu-im-channel` | **Date**: 2026-08-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/017-feishu-im-channel/spec.md`

## Summary

在 `oryxos-core` 从零沉淀入站 IM 渠道契约（归一化消息 + 薄适配器接口 + 共享编排服务），并交付第一个适配器模块 `oryxos-channel-feishu`：用飞书官方 `oapi-sdk 2.8.5` 的低层 `ws.Client + EventDispatcher` 建长连接收 `im.message.receive_v1` 事件（免公网回调、免验签），归一化后由 core 编排服务完成去重（message_id + 进程内 TTL 缓存）、绑定路由（channels.yaml 一应用一 Agent）、私聊持久会话（`process`）/ 群聊无状态问答（`processStateless` 加渠道标签重载）分流、错误与「处理中」回复、审计提交（`triggerAsync` 虚拟线程解耦秒级确认与耗时推理）；回复经沙箱显式校验后走 `im/v1/messages`（群聊 reply 引用原消息、超长按配置分段）。配置与热更完全复刻 MCP 三件套（`.oryxos/channels.yaml` 双读法 loader + `synchronized` AdminService + REST `/api/v1/channels` CRUD/status）。契约行为由参数化测试集钉死（飞书档 + 测试桩档），证明第二个 IM 渠道零 core 修改可接入。

## Technical Context

**Language/Version**: Java 21（虚拟线程）

**Primary Dependencies**: Spring Boot 3.x（仅 web/装配层）、`com.larksuite.oapi:oapi-sdk:2.8.5`（长连接 + IM API；vendored okhttp，传递依赖 guava 32.0.0-jre 需对齐检查）、SnakeYAML（channels.yaml）

**Storage**: SQLite（复用 `sessions` / `llm_calls` / `tool_invocations` / `agent_executions`，**零 DDL 变更**）；`.oryxos/channels.yaml`（渠道绑定与凭证占位，POSIX `rw-------`）；去重缓存为进程内有界 TTL 结构（单实例语义）

**Testing**: JUnit 5 + Mockito；核心为参数化契约测试集（`InboundMessageService` × {飞书归一化档, 测试桩档}），另有飞书归一化单测（mention 剥离/非文本/分段）与 REST 端点测试；`mvn verify` 全量质量门禁（Spotless / P3C / Checkstyle / SpotBugs / OWASP）

**Target Platform**: Linux server（企业内网，仅需出方向公网访问 open.feishu.cn）

**Project Type**: Maven 多模块单体新增一个 channel 模块 + core 契约包

**Performance Goals**: 渠道端到端开销（除模型推理）≤ 2s P95（SC-001）；事件 handler 3 秒内返回（飞书平台硬时限）；去重有效率 100%（SC-004）

**Constraints**: 同步阻塞 + 虚拟线程（禁 Reactor/CompletableFuture）；凭证 `${ENV}` 占位不落明文；渠道启停/绑定变更免重启生效；单实例部署语义

**Scale/Scope**: 单实例、单位数飞书应用并存（一应用一连接一 Agent）；消息吞吐受飞书频控（单用户/群 5 QPS）天然限制

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 说明 |
|------|------|------|
| I 自实现 ReAct 循环 | ✅ 通过 | 不触碰循环；渠道只在循环外调用 `AgentService.process/processStateless` |
| II Spring AI 仅协议转换 | ✅ 通过 | 渠道模块不引入任何 Spring AI 依赖 |
| III Provider 显式映射 | ✅ 不涉及 | 无 Provider 变更 |
| IV 目录=Agent / Skill 软连接 | ✅ 通过 | 绑定放 `.oryxos/channels.yaml`（方向：飞书应用→Agent），不动 AGENT.md frontmatter，不启用僵尸字段 `Profile.channels`，避免双真相源 |
| V 审计 Day One 落库 | ✅ 通过 | 私聊经 `process` 走既有 `llm_calls`/`tool_invocations`；群聊经 `processStateless` 重载带 `feishu-group:` 前缀；执行经 `triggerAsync(source="feishu")` 落 `agent_executions` |
| VI 沙箱与凭证 | ✅ 通过 | app_secret 走 `${ENV}` 占位，缺失点名报错不上线（R2）；出站发送前显式 `sandbox.enforce(HTTP_REQUEST)`（R7）；`*.feishu.cn` 补进运行配置白名单 |
| VII 同步 + 虚拟线程 | ✅ 通过 | 低层 `ws.Client`（同步语义）而非 `LarkChannel`（CompletableFuture API）；解耦用 `triggerAsync` 虚拟线程 + CountDownLatch 纯同步原语（R4） |
| VIII 目录配置即 Agent / 状态外置 | ✅ 通过 | 会话状态在 SQLite；去重缓存是可丢失缓存（重启窗口已被 spec Edge Case 豁免），非业务状态 |
| 模块演进声明 | ✅ 已声明 | 新建 `oryxos-channel-feishu`：渠道适配器按「新增 Channel 只加新模块」原则独立成模块，与 `oryxos-channel-cli` 平级；跨模块契约放 `oryxos-core/channel/`（依赖倒置）。CLAUDE.md 模块表与 `docs/TechnicalSolution.md` §10 同步更新列入任务 |

**Post-design re-check（Phase 1 后复核）**: 设计产物未引入新违背项；`AgentService.processStateless` 重载属 core 的一次性契约补齐（本期建立契约本身），后续渠道零 core 修改的验证由 SC-007 测试桩钉死。通过。

## Project Structure

### Documentation (this feature)

```text
specs/017-feishu-im-channel/
├── plan.md              # 本文件
├── research.md          # Phase 0（10 项决策 R1~R10）
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   ├── inbound-channel-contract.md   # Java 契约 + 行为规则 + 契约测试集清单
│   └── channels-api.md               # REST /api/v1/channels
└── tasks.md             # Phase 2（/speckit-tasks 产出，非本命令）
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxos/core/channel/
├── InboundMessage.java            # 归一化入站消息（record）
├── ChatKind.java                  # P2P / GROUP
├── InboundChannelAdapter.java     # 适配器契约：start/stop/status/sendReply
├── ChannelStatus.java             # 渠道在线状态（record）
├── ChannelBinding.java            # 一应用一 Agent 绑定（record）
├── ChannelConfig.java             # channels.yaml 条目模型
├── ChannelConfigLoader.java       # load(resolved)/loadRaw/save，复刻 McpConfigLoader
├── InboundChannelRegistry.java    # name → 运行中适配器，活视图
├── InboundMessageService.java     # 共享编排：去重/路由/分流/回复/审计（契约测试对象）
├── MessageDeduplicator.java       # message_id 有界 TTL 去重
└── ChannelAdminService.java       # add/update/remove/reload：落盘+断旧+建新，免重启

oryxos-core/src/main/java/io/oryxos/core/agent/
└── AgentService.java              # 仅新增 processStateless(name, msg, executionTag) 重载

oryxos-channel-feishu/src/main/java/io/oryxos/channel/feishu/
├── FeishuChannelAdapter.java      # InboundChannelAdapter 实现：ws.Client 生命周期
├── FeishuEventNormalizer.java     # 事件→InboundMessage：@ 判断/剥离、非文本识别
├── FeishuMessageSender.java       # 回复发送：sandbox.enforce、分段、reply 引用、uuid 幂等
└── ChannelFeishuModule.java       # 空 marker（仿 ChannelCliModule）

oryxos-web/src/main/java/io/oryxos/web/controller/
├── ChannelApiController.java      # /api/v1/channels CRUD + /status
└── dto/ChannelStatusView.java     # + ChannelView/ChannelRequest

oryxos-cli/src/main/java/io/oryxos/cli/
└── OryxOsRuntime.java             # 显式 @Bean 装配（loader/registry/service/admin/adapter 工厂）

oryxos-core/src/test/java/io/oryxos/core/channel/
├── InboundMessageServiceContractTest.java   # 参数化契约测试集（桩档）
├── StubChannelAdapter.java                  # 测试桩渠道（SC-007 证据）
└── ...（去重/loader/admin 单测）

oryxos-channel-feishu/src/test/java/io/oryxos/channel/feishu/
└── ...（归一化/分段/发送单测 + 契约测试集飞书档）

配置面：
├── pom.xml                        # <modules> + dependencyManagement 加 oryxos-channel-feishu
├── oryxos-cli/pom.xml             # 依赖新模块（Runtime 装配需要）
├── oryxos-boot/pom.xml            # 依赖新模块（fat jar）
├── config/application.yml         # http.allowed_domains 补 *.feishu.cn
└── .oryxos/channels.yaml          # 运行时生成/维护（不入库，example 入 config/）
```

**Structure Decision**: 契约与编排收敛在 `oryxos-core/channel/`（跨模块契约放 core、下游实现，依赖倒置）；`oryxos-channel-feishu` 为纯 POJO 薄适配器模块，仅依赖 core + oapi-sdk，经 `OryxOsRuntime` 显式 `@Bean` 装配（与 CliChannel 同款）；REST 面在 `oryxos-web`。审计与会话零 schema 变更，全部复用既有写入点。

## 与既有资产的复用映射（探索结论摘要）

| 需求 | 复用 | 方式 |
|------|------|------|
| 私聊多轮会话（FR-006） | `SessionManager.getOrCreate("feishu", openId, agent)` + `AgentService.process` | 零改动白拿（隔离/窗口/锁/乐观并发） |
| 群聊无状态问答（FR-006） | `AgentService.processStateless` | 新增 executionTag 重载（审计渠道可辨） |
| 确认/推理解耦（FR-008） | `AgentExecutionService.triggerAsync(…, "feishu", …)` | 复刻 `AgentApiController.trigger` 骨架 |
| 配置热更（FR-013） | `McpConfigLoader` + `McpServerAdminService` 模式 | 复刻为 ChannelConfigLoader/ChannelAdminService |
| 凭证占位（FR-012） | `${ENV}` 正则口径 + `ProvidersProperties.validate` 的点名报错 | 组合两个既有口径 |
| 出站沙箱（复用白名单） | `WhitelistSandbox.enforce(HTTP_REQUEST)` | 发送前显式接线（R7，默认不拦必须主动接） |
| 渠道状态（FR-014） | `McpApiController#status` 形态 | `/api/v1/channels/status` |
| 审计（FR-014/SC-006） | 既有四表写入点 | 零 DDL；渠道由 sessions.channel + session_id 前缀 + executions.source 区分 |

## Complexity Tracking

> 无宪法违背项，无需豁免论证。

（唯一接近点：`AgentService` 增加一个重载——属建立渠道契约的一次性 core 补齐，非「新增渠道改 core」；SC-007 的测试桩以零 core diff 验证后续渠道接入。）
