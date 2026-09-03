package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.session.ImageMime;
import io.oryxos.core.session.Message;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InboundMediaPartsTest {

  @Test
  @DisplayName("仅提取带 url 的图片附件")
  void extractsImageUrlsOnly() {
    InboundMessage msg =
        new InboundMessage(
            "feishu",
            "ops-feishu",
            "m1",
            ChatKind.P2P,
            "u1",
            "c1",
            "",
            false,
            false,
            List.of(
                InboundAttachment.imageUrl("https://example.com/a.png"),
                InboundAttachment.imageReference("img_only_key"),
                InboundAttachment.imageUrl("C:\\tmp\\b.jpg")));

    List<Message.MediaPart> parts = InboundMediaParts.from(msg);
    assertEquals(2, parts.size());
    assertEquals("https://example.com/a.png", parts.get(0).uri());
    assertEquals(ImageMime.IMAGE_PNG, parts.get(0).mimeType());
    assertTrue(parts.get(1).uri().endsWith("b.jpg"));
  }

  @Test
  @DisplayName("无附件 → 空列表")
  void emptyWhenNoAttachments() {
    InboundMessage msg =
        new InboundMessage(
            "feishu", "ops-feishu", "m2", ChatKind.P2P, "u1", "c1", "hi", true, false, List.of());
    assertTrue(InboundMediaParts.from(msg).isEmpty());
  }
}
