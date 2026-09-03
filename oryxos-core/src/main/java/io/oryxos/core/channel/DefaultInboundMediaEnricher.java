package io.oryxos.core.channel;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认入站媒体富化：文本直传；图片/文件转为说明文案。图片另由 {@link InboundMediaParts} 供 Vision；文件只走文案路径（本地路径供 {@code
 * read_file}）。
 */
public final class DefaultInboundMediaEnricher implements InboundMediaEnricher {

  private static final String IMAGE_WITH_URL = "[用户发送了一张图片]\n图片链接: ";
  private static final String IMAGE_WITH_REF = "[用户发送了一张图片]\n图片资源: ";
  private static final String FILE_WITH_URL = "[用户发送了一个文件]\n本地路径: ";
  private static final String FILE_WITH_REF = "[用户发送了一个文件]\n文件资源: ";
  private static final String FILE_HINT = "\n可用 read_file 读取该路径（须在 FILE 沙箱白名单内）。";

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
      } else if (InboundAttachment.TYPE_FILE.equals(attachment.type())) {
        if (attachment.url() != null && !attachment.url().isBlank()) {
          parts.add(FILE_WITH_URL + attachment.url().strip() + FILE_HINT);
        } else if (attachment.reference() != null && !attachment.reference().isBlank()) {
          parts.add(FILE_WITH_REF + attachment.reference().strip());
        }
      }
    }
    return String.join("\n\n", parts).strip();
  }
}
