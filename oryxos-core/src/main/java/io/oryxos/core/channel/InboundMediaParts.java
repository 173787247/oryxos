package io.oryxos.core.channel;

import io.oryxos.core.session.ImageMime;
import io.oryxos.core.session.Message;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 从入站附件提取可交给 vision 模型的 {@link Message.MediaPart}（仅有可解析 {@code url} 的图片）。 */
public final class InboundMediaParts {

  private InboundMediaParts() {}

  public static List<Message.MediaPart> from(InboundMessage message) {
    if (message == null || message.attachments().isEmpty()) {
      return List.of();
    }
    List<Message.MediaPart> parts = new ArrayList<>();
    for (InboundAttachment attachment : message.attachments()) {
      if (!InboundAttachment.TYPE_IMAGE.equals(attachment.type())) {
        continue;
      }
      String url = attachment.url();
      if (url == null || url.isBlank()) {
        continue;
      }
      parts.add(new Message.MediaPart(resolveMime(url.strip()), url.strip()));
    }
    return List.copyOf(parts);
  }

  private static String resolveMime(String url) {
    if (ImageMime.isHttpUrl(url)) {
      return ImageMime.fromPath(url);
    }
    Path path = Path.of(url);
    if (Files.isRegularFile(path)) {
      return ImageMime.probeFile(path);
    }
    return ImageMime.fromPath(url);
  }
}
