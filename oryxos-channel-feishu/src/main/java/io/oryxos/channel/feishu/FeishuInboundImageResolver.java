package io.oryxos.channel.feishu;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.GetMessageResourceResp;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书入站图片：官方无公开临时 URL，须用 message_id + image_key 调「获取消息中的资源文件」下载二进制。成功后把本地绝对路径写入 {@link
 * InboundAttachment#url()}，enricher 即可与企微一样输出「图片链接」。
 *
 * <p>下载失败时保留原 {@code image_key} 引用（降级，不阻断编排）。
 */
final class FeishuInboundImageResolver {

  private static final Logger LOG = LoggerFactory.getLogger(FeishuInboundImageResolver.class);

  private static final String RESOURCE_TYPE_IMAGE = "image";
  private static final int MAX_SEGMENT_LEN = 96;

  private final Client client;
  private final Path mediaRoot;
  private final String channelName;

  FeishuInboundImageResolver(Client client, Path mediaRoot, String channelName) {
    this.client = client;
    this.mediaRoot = mediaRoot;
    this.channelName = channelName;
  }

  InboundMessage resolve(InboundMessage message) {
    if (message.attachments().isEmpty()) {
      return message;
    }
    List<InboundAttachment> resolved = new ArrayList<>(message.attachments().size());
    boolean changed = false;
    for (InboundAttachment attachment : message.attachments()) {
      if (!needsDownload(attachment)) {
        resolved.add(attachment);
        continue;
      }
      InboundAttachment next = downloadOrKeep(message.messageId(), attachment);
      changed |= next != attachment;
      resolved.add(next);
    }
    if (!changed) {
      return message;
    }
    return new InboundMessage(
        message.channelType(),
        message.channelName(),
        message.messageId(),
        message.chatKind(),
        message.userId(),
        message.chatId(),
        message.content(),
        message.textual(),
        message.mentionedBot(),
        resolved);
  }

  private static boolean needsDownload(InboundAttachment attachment) {
    return InboundAttachment.TYPE_IMAGE.equals(attachment.type())
        && (attachment.url() == null || attachment.url().isBlank())
        && attachment.reference() != null
        && !attachment.reference().isBlank();
  }

  private InboundAttachment downloadOrKeep(String messageId, InboundAttachment attachment) {
    String imageKey = attachment.reference();
    try {
      GetMessageResourceResp resp =
          client
              .im()
              .messageResource()
              .get(
                  GetMessageResourceReq.newBuilder()
                      .messageId(messageId)
                      .fileKey(imageKey)
                      .type(RESOURCE_TYPE_IMAGE)
                      .build());
      if (resp == null || !resp.success() || resp.getData() == null) {
        LOG.warn(
            "飞书渠道 {} 下载图片失败（messageId={}, imageKey={}, code={}, msg={}），保留 image_key",
            sanitize(channelName),
            sanitize(messageId),
            sanitize(imageKey),
            resp == null ? -1 : resp.getCode(),
            sanitize(resp == null ? null : resp.getMsg()));
        return attachment;
      }
      Path file = writeToMediaRoot(messageId, imageKey, resp);
      return new InboundAttachment(
          InboundAttachment.TYPE_IMAGE, file.toAbsolutePath().toString(), imageKey);
    } catch (Exception e) {
      LOG.warn(
          "飞书渠道 {} 下载图片异常（messageId={}, imageKey={}）：{}，保留 image_key",
          sanitize(channelName),
          sanitize(messageId),
          sanitize(imageKey),
          sanitize(e.getMessage()));
      return attachment;
    }
  }

  private Path writeToMediaRoot(String messageId, String imageKey, GetMessageResourceResp resp)
      throws IOException {
    String fileName = resp.getFileName();
    String ext = extensionOf(fileName);
    Path dir = mediaRoot.resolve(safeSegment(messageId));
    Files.createDirectories(dir);
    Path target = dir.resolve(safeSegment(imageKey) + ext);
    try (OutputStream out = Files.newOutputStream(target)) {
      resp.getData().writeTo(out);
    }
    return target;
  }

  private static String extensionOf(String fileName) {
    if (fileName == null || fileName.isBlank()) {
      return ".bin";
    }
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) {
      return ".bin";
    }
    String ext = fileName.substring(dot).toLowerCase(Locale.ROOT);
    if (!ext.matches("\\.[a-z0-9]{1,8}")) {
      return ".bin";
    }
    return ext;
  }

  static String safeSegment(String raw) {
    if (raw == null || raw.isBlank()) {
      return "x";
    }
    String cleaned = raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    if (cleaned.length() > MAX_SEGMENT_LEN) {
      cleaned = cleaned.substring(0, MAX_SEGMENT_LEN);
    }
    if (cleaned.isBlank() || cleaned.chars().allMatch(ch -> ch == '_')) {
      return "x";
    }
    return cleaned;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
