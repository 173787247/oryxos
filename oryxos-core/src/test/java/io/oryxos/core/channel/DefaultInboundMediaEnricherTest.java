package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultInboundMediaEnricherTest {

  private final DefaultInboundMediaEnricher enricher = new DefaultInboundMediaEnricher();

  @Test
  @DisplayName("文本消息原样传递")
  void textOnly() {
    InboundMessage msg =
        new InboundMessage(
            "feishu", "ops-feishu", "m1", ChatKind.P2P, "u1", "c1", "你好", true, false, List.of());
    assertEquals("你好", enricher.toAgentInput(msg));
  }

  @Test
  @DisplayName("图片 URL 附件转为 Agent 可消费说明")
  void imageUrlAttachment() {
    InboundMessage msg =
        new InboundMessage(
            "wecom",
            "ops-wecom",
            "m2",
            ChatKind.P2P,
            "u1",
            "c1",
            "",
            false,
            false,
            List.of(InboundAttachment.imageUrl("https://example/img.png")));
    String input = enricher.toAgentInput(msg);
    assertTrue(input.contains("https://example/img.png"));
    assertTrue(input.contains("图片"));
  }

  @Test
  @DisplayName("飞书 image_key 作为资源引用传递")
  void imageReferenceAttachment() {
    InboundMessage msg =
        new InboundMessage(
            "feishu",
            "ops-feishu",
            "m3",
            ChatKind.P2P,
            "u1",
            "c1",
            "",
            false,
            false,
            List.of(InboundAttachment.imageReference("img_abc")));
    assertTrue(enricher.toAgentInput(msg).contains("img_abc"));
  }
}
