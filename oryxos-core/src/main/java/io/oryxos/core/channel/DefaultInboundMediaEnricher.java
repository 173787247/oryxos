package io.oryxos.core.channel;

import java.util.ArrayList;
import java.util.List;

/** 默认入站媒体富化：文本直传；图片转为带链接/资源 id 的说明，供 Agent 或 vision 模型继续处理。 */
public final class DefaultInboundMediaEnricher implements InboundMediaEnricher {

  private static final String IMAGE_WITH_URL = "[用户发送了一张图片]\n图片链接: ";
  private static final String IMAGE_WITH_REF = "[用户发送了一张图片]\n图片资源: ";

  @Override
  public String toAgentInput(InboundMessage message) {
    List<String> parts = new ArrayList<>();
    if (message.content() != null && !message.content().isBlank()) {
      parts.add(message.content().strip());
    }
    for (InboundAttachment attachment : message.attachments()) {
      if (InboundAttachment.TYPE_IMAGE.equals(attachment.type())) {
        if (attachment.url() != null && !attachment.url().isBlank()) {
          parts.add(IMAGE_WITH_URL + attachment.url().strip());
        } else if (attachment.reference() != null && !attachment.reference().isBlank()) {
          parts.add(IMAGE_WITH_REF + attachment.reference().strip());
        }
      }
    }
    return String.join("\n\n", parts).strip();
  }
}
