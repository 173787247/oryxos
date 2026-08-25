package io.oryxos.core.channel;

/**
 * 契约测试集·桩档（017 T024 / SC-007）：直接构造归一化消息——证明第二个入站渠道只需产出 {@link InboundMessage} 即可零 core 修改跑通全部契约行为。
 */
class StubInboundContractTest extends InboundMessageServiceContractTestBase {

  @Override
  protected String channelType() {
    return "stub";
  }

  @Override
  protected InboundMessage p2pMessage(String messageId, String content) {
    return new InboundMessage(
        channelType(),
        "contract-chan",
        messageId,
        ChatKind.P2P,
        "user-1",
        "chat-p2p",
        content,
        true,
        false);
  }

  @Override
  protected InboundMessage groupMessage(String messageId, String content) {
    return new InboundMessage(
        channelType(),
        "contract-chan",
        messageId,
        ChatKind.GROUP,
        "user-1",
        "chat-grp",
        content,
        true,
        true);
  }

  @Override
  protected InboundMessage nonTextualMessage(String messageId) {
    return new InboundMessage(
        channelType(),
        "contract-chan",
        messageId,
        ChatKind.P2P,
        "user-1",
        "chat-p2p",
        "",
        false,
        false);
  }
}
