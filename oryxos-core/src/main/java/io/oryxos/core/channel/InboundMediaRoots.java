package io.oryxos.core.channel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入站媒体落盘根目录：优先 {@code user.dir/.oryxos/inbound-media/{channel}}（通常在 FILE 沙箱 {@code .oryxos}
 * 白名单内），失败再退到临时目录。
 */
public final class InboundMediaRoots {

  private static final Logger LOG = LoggerFactory.getLogger(InboundMediaRoots.class);
  private static final String INBOUND_MEDIA = "inbound-media";
  private static final int MAX_SEGMENT_LEN = 96;

  private InboundMediaRoots() {}

  public static Path forChannel(String channelName, String tempPrefix) {
    String channelSeg = safeSegment(channelName);
    Path preferred = Path.of(System.getProperty("user.dir"), ".oryxos", INBOUND_MEDIA, channelSeg);
    try {
      Files.createDirectories(preferred);
      return preferred;
    } catch (IOException e) {
      LOG.warn(
          "创建入站媒体目录失败（{}），回退临时目录: {}",
          preferred,
          e.getMessage() == null ? "" : e.getMessage().replace('\r', '_').replace('\n', '_'));
      try {
        Path root = Files.createTempDirectory(tempPrefix);
        Path channelDir = root.resolve(channelSeg);
        Files.createDirectories(channelDir);
        return channelDir;
      } catch (IOException fallback) {
        Path last =
            Path.of(
                System.getProperty("java.io.tmpdir"),
                tempPrefix.replaceAll("[^a-zA-Z0-9_-]", "_"),
                channelSeg);
        try {
          Files.createDirectories(last);
        } catch (IOException ignored) {
          // 调用方下载时再失败
        }
        return last;
      }
    }
  }

  static String safeSegment(String raw) {
    if (raw == null || raw.isBlank()) {
      return "x";
    }
    String cleaned = raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    if (cleaned.length() > MAX_SEGMENT_LEN) {
      cleaned = cleaned.substring(0, MAX_SEGMENT_LEN);
    }
    return cleaned.isBlank() ? "x" : cleaned;
  }
}
