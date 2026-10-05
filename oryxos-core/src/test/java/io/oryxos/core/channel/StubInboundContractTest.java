package io.oryxos.core.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.oryxos.core.agent.InterruptManager;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        false,
        List.of());
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
        true,
        List.of());
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
        false,
        List.of());
  }

  @Override
  protected InboundMessage imageMessage(String messageId) {
    return new InboundMessage(
        channelType(),
        "contract-chan",
        messageId,
        ChatKind.P2P,
        "user-1",
        "chat-p2p",
        "",
        false,
        false,
        List.of(InboundAttachment.imageUrl("https://example/img.png")));
  }

  // ── #795：集群档 /stop 落在非执行副本时的回话 ──────────────────────────────
  // 底座用的是 7 参数构造器（interruptManager = null），会走 handleStopCommand 的第一个
  // 分支直接回原文案 —— 所以这两条测试自己造带 interruptManager 的 service（8 参数 ✓）。

  private InboundMessageService serviceWithInterruptManager() {
    return new InboundMessageService(
        agentService,
        sessionManager,
        profileRegistry,
        executionService,
        new InMemoryMessageDeduplicator(),
        null,
        Duration.ofSeconds(30),
        new InterruptManager());
  }

  @Test
  @DisplayName("#795 租约在别的副本手里 ⇒ 不能回「没有正在执行的任务」")
  void stopOnReplicaWithoutLocalRun_saysItMayRunElsewhere() {
    when(sessionManager.findSessionId(any(), any(), any())).thenReturn(Optional.of("s-1"));
    service = serviceWithInterruptManager();
    service.setTurnLeaseLookup(sessionId -> true); // 另一副本正持有该会话的 turn

    service.onMessage(p2pMessage("m-1", "/stop"), replyChannel);

    assertThat(replyChannel.sent())
        .extracting(StubChannelAdapter.SentReply::text)
        .contains(InboundMessageService.STOP_ELSEWHERE_REPLY);
  }

  @Test
  @DisplayName("#795 反证：没有任何别的副本持租约 ⇒ 仍回原文案（判据能失败）")
  void stopWithoutAnyRun_keepsTheOriginalReply() {
    when(sessionManager.findSessionId(any(), any(), any())).thenReturn(Optional.of("s-1"));
    service = serviceWithInterruptManager(); // ★ 不接 lookup ⇒ 默认 NONE

    service.onMessage(p2pMessage("m-2", "/stop"), replyChannel);

    assertThat(replyChannel.sent())
        .extracting(StubChannelAdapter.SentReply::text)
        .contains(InboundMessageService.STOP_NO_SESSION_REPLY);
  }
}
