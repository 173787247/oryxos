package io.oryxos.channel.feishu;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.GetMessageResourceResp;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.session.ImageMime;
import io.oryxos.core.session.InboundMediaExt;
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
  private static final String RESOURCE_TYPE_FILE = "file";
  private static final String DEFAULT_EXTENSION = ".bin";
  private static final String FALLBACK_SEGMENT = "x";
  private static final String SAFE_EXTENSION_PATTERN = "\\.[a-z0-9]{1,8}";
  private static final char PATH_SAFE_REPLACEMENT = '_';
  private static final int MAX_SEGMENT_LEN = 96;
  private static final int DOWNLOAD_ATTEMPTS = 2;
  private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

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
    if (attachment.reference() == null || attachment.reference().isBlank()) {
      return false;
    }
    if (attachment.url() != null && !attachment.url().isBlank()) {
      return false;
    }
    String type = attachment.type();
    return InboundAttachment.TYPE_IMAGE.equals(type)
        || InboundAttachment.TYPE_FILE.equals(type)
        || InboundAttachment.TYPE_AUDIO.equals(type)
        || InboundAttachment.TYPE_VIDEO.equals(type);
  }

  private InboundAttachment downloadOrKeep(String messageId, InboundAttachment attachment) {
    String fileKey = attachment.reference();
    boolean fileLike =
        InboundAttachment.TYPE_FILE.equals(attachment.type())
            || InboundAttachment.TYPE_AUDIO.equals(attachment.type())
            || InboundAttachment.TYPE_VIDEO.equals(attachment.type());
    String resourceType = fileLike ? RESOURCE_TYPE_FILE : RESOURCE_TYPE_IMAGE;
    String kind =
        InboundAttachment.TYPE_AUDIO.equals(attachment.type())
            ? "语音"
            : (InboundAttachment.TYPE_VIDEO.equals(attachment.type())
                ? "视频"
                : (fileLike ? "文件" : "图片"));
    Exception last = null;
    for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
      try {
        GetMessageResourceResp resp =
            client
                .im()
                .messageResource()
                .get(
                    GetMessageResourceReq.newBuilder()
                        .messageId(messageId)
                        .fileKey(fileKey)
                        .type(resourceType)
                        .build());
        if (resp == null || !resp.success() || resp.getData() == null) {
          LOG.warn(
              "飞书渠道 {} 下载{}失败（messageId={}, key={}, code={}, msg={}），保留原引用",
              sanitize(channelName),
              kind,
              sanitize(messageId),
              sanitize(fileKey),
              resp == null ? -1 : resp.getCode(),
              sanitize(resp == null ? null : resp.getMsg()));
          return attachment;
        }
        Path path = writeToMediaRoot(messageId, fileKey, resp, fileLike);
        return new InboundAttachment(
            attachment.type(), path.toAbsolutePath().toString(), fileKey, attachment.fileName());
      } catch (Exception e) {
        last = e;
        if (attempt < DOWNLOAD_ATTEMPTS && isTransientTimeout(e)) {
          LOG.warn(
              "飞书渠道 {} 下载{}超时重试 {}/{}（messageId={}, key={}）：{}",
              sanitize(channelName),
              kind,
              attempt,
              DOWNLOAD_ATTEMPTS,
              sanitize(messageId),
              sanitize(fileKey),
              sanitize(e.getMessage()));
          continue;
        }
        break;
      }
    }
    LOG.warn(
        "飞书渠道 {} 下载{}异常（messageId={}, key={}）：{}，保留原引用",
        sanitize(channelName),
        kind,
        sanitize(messageId),
        sanitize(fileKey),
        sanitize(last == null ? null : last.getMessage()));
    return attachment;
  }

  private static boolean isTransientTimeout(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      String name = t.getClass().getName();
      if (name.contains("Timeout") || name.contains("InterruptedIO")) {
        return true;
      }
      String msg = t.getMessage();
      if (msg != null) {
        String lower = msg.toLowerCase(Locale.ROOT);
        if (lower.contains("timeout") || lower.contains("timed out")) {
          return true;
        }
      }
    }
    return false;
  }

  private Path writeToMediaRoot(
      String messageId, String fileKey, GetMessageResourceResp resp, boolean fileAttachment)
      throws IOException {
    String fileName = resp.getFileName();
    String ext = extensionOf(fileName);
    Path dir = mediaRoot.resolve(safeSegment(messageId));
    Files.createDirectories(dir);
    Path target = dir.resolve(safeSegment(fileKey) + ext);
    try (OutputStream out = Files.newOutputStream(target)) {
      resp.getData().writeTo(out);
    }
    long size = Files.size(target);
    if (size > MAX_FILE_BYTES) {
      try {
        Files.deleteIfExists(target);
      } catch (IOException ignored) {
        // 尽力删除超限文件
      }
      throw new IOException("入站文件超过上限 " + MAX_FILE_BYTES + " 字节");
    }
    // 图片常无后缀：用魔数改扩展名；文件无后缀时嗅探 PDF
    if (!fileAttachment && DEFAULT_EXTENSION.equals(ext)) {
      String sniffed = ImageMime.probeFile(target);
      String betterExt = ImageMime.extensionFor(sniffed);
      if (!DEFAULT_EXTENSION.equals(betterExt) && !betterExt.equals(ext)) {
        Path renamed = dir.resolve(safeSegment(fileKey) + betterExt);
        try {
          Files.move(target, renamed);
          return renamed;
        } catch (IOException moveFailed) {
          LOG.debug("飞书图片重命名扩展名失败，保留原文件: {}", sanitize(moveFailed.getMessage()));
        }
      }
    } else if (fileAttachment) {
      String better = InboundMediaExt.betterFileExtension(target, ext);
      if (better != null) {
        Path renamed = dir.resolve(safeSegment(fileKey) + better);
        try {
          Files.move(target, renamed);
          return renamed;
        } catch (IOException moveFailed) {
          LOG.debug("飞书文件 PDF 扩展名重命名失败，保留原文件: {}", sanitize(moveFailed.getMessage()));
        }
      }
    }
    return target;
  }

  private static String extensionOf(String fileName) {
    if (fileName == null || fileName.isBlank()) {
      return DEFAULT_EXTENSION;
    }
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) {
      return DEFAULT_EXTENSION;
    }
    String ext = fileName.substring(dot).toLowerCase(Locale.ROOT);
    if (!ext.matches(SAFE_EXTENSION_PATTERN)) {
      return DEFAULT_EXTENSION;
    }
    return ext;
  }

  static String safeSegment(String raw) {
    if (raw == null || raw.isBlank()) {
      return FALLBACK_SEGMENT;
    }
    String cleaned = raw.replaceAll("[^a-zA-Z0-9._-]", String.valueOf(PATH_SAFE_REPLACEMENT));
    if (cleaned.length() > MAX_SEGMENT_LEN) {
      cleaned = cleaned.substring(0, MAX_SEGMENT_LEN);
    }
    if (cleaned.isBlank() || cleaned.chars().allMatch(ch -> ch == PATH_SAFE_REPLACEMENT)) {
      return FALLBACK_SEGMENT;
    }
    return cleaned;
  }

  private static String sanitize(String value) {
    return value == null
        ? ""
        : value.replace('\r', PATH_SAFE_REPLACEMENT).replace('\n', PATH_SAFE_REPLACEMENT);
  }
}
