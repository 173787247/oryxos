# Contract: 入站 IM 渠道契约（`io.oryxos.core.channel`）

**Feature**: 017 | **满足**: FR-010（契约沉淀）、SC-007（测试桩零 core 修改）

新增 IM 渠道 = 实现 `InboundChannelAdapter` + 在适配器工厂注册类型，**其余全部白拿**。语义（去重、路由、会话、回复文案、审计）收敛在 `InboundMessageService`，适配器不得复制这些逻辑。

## Java 契约

```java
/** 适配器契约：一实例 = 一个平台应用的一条接入。纯 POJO，禁 Spring 依赖。 */
public interface InboundChannelAdapter {
    String name();                 // channels.yaml 条目名
    String type();                 // "feishu" | "stub" | ...
    void start();                  // 建立长连接；失败抛出带点名原因的异常（不得静默）
    void stop();                   // 幂等断开
    ChannelStatus status();        // 实时状态（CONNECTED/DISCONNECTED/DISABLED/ERROR）
    /** 发送回复。replyToMessageId 非空时引用原消息（群聊必传）；实现负责平台上限分段与出站沙箱校验。 */
    void sendReply(String chatId, String text, String replyToMessageId);
}

/** 编排服务（core 实现，契约测试对象）。适配器收到归一化消息后唯一入口。 */
public final class InboundMessageService {
    /** 平台确认线程内快速返回（去重+提交），推理与回发走虚拟线程。 */
    public void onMessage(InboundMessage msg, InboundChannelAdapter replyVia);
}
```

## 行为规则（编排服务，参数化契约测试集逐条钉死）

| # | 规则 | 来源 |
|---|------|------|
| B1 | 同一 `channelName:messageId` 重复到达只处理一次，用户仅收 1 条回答 | FR-004/SC-004 |
| B2 | `P2P` → `getOrCreate("feishu", userId, agent)` + `process`；多轮承接上下文 | FR-006 |
| B3 | `GROUP` → `processStateless(agent, content, "feishu-group")`；互不携带彼此上下文，不落 sessions 表 | FR-006/SC-009 |
| B4 | 回复送回来源 `chatId`；群聊带 `replyToMessageId` 引用原消息 | FR-007 |
| B5 | `onMessage` 在确认线程内不跑推理（去重+提交后即返回）；推理在 `triggerAsync(source="feishu")` 虚拟线程 | FR-008 |
| B6 | 处理失败（Agent 不存在/推理异常/迭代耗尽）→ 回复可读失败说明，不含堆栈，不静默 | FR-008/US1-AS3 |
| B7 | `textual==false` → 回复「当前仅支持文本提问」类能力说明 | FR-009 |
| B8 | 超过「处理中」阈值（可配，默认 15s）仍未完成 → 先行发送一条处理中提示，最终回答照发 | Edge Case |
| B9 | 绑定 Agent 不存在（`profileRegistry` 查空）→ 回复「Agent 不可用」，审计照落 | Edge Case |
| B10 | 每次触发落 `agent_executions(source="feishu")`；私聊落 sessions，群聊 session_id 前缀 `feishu-group:` | FR-014/SC-006 |

**适配器侧规则**（飞书档单测钉死，桩档天然满足）：
- A1 非 @ 机器人的群消息在归一化层丢弃——不进编排、不落任何记录（SC-002）。
- A2 @ 机器人片段从 `content` 剥离；其余 mention 占位符替换为人名（FR-002）。
- A3 `sendReply` 超平台上限按配置分段顺序发送，内容不丢（FR-009）；发送前 `sandbox.enforce(HTTP_REQUEST, url)`。
- A4 `start` 前置校验凭证 resolved（不含 `${`）与绑定 Agent 存在；失败点名报错、该渠道不上线、不影响其余（FR-013/SC-008）。

## 契约测试集（SC-007 的证据）

`InboundMessageServiceContractTest`（JUnit 5 `@ParameterizedTest`）对 B1~B10 逐条断言，参数源：
1. **StubChannelAdapter**（`oryxos-core` test 内的测试桩，内存收发）——证明第二个渠道零 core diff 接入；
2. **飞书档**（`oryxos-channel-feishu` test 引用同一测试基类，注入 FeishuEventNormalizer 产出的归一化消息 + mock 发送端）。

判定标准：两档全绿且 `git diff oryxos-core`（相对契约建立提交）为空。
