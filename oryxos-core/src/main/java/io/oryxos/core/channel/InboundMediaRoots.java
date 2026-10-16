package io.oryxos.core.channel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入站媒体落盘根目录：优先 {@code <工作区根>/inbound-media/{channel}}（工作区根在 FILE 沙箱 {@code .oryxos}
 * 白名单内），失败再退到临时目录。
 *
 * <p>工作区根由调用方注入，本类不读 {@code user.dir}：运行时白名单只加**配置的工作区根**（{@code oryxos.root}/{@code
 * ORYXOS_ROOT}），cwd 与根不一定是同一个目录——按 cwd 落盘会让入站媒体跑到白名单外，富化器交给 Agent 的 {@code read_file}
 * 随即被沙箱拒绝，功能静默不可用。
 */
public final class InboundMediaRoots {

  private static final Logger LOG = LoggerFactory.getLogger(InboundMediaRoots.class);
  private static final String INBOUND_MEDIA = "inbound-media";
  private static final String FALLBACK_TEMP_PREFIX = "oryxos-inbound-media-";

  /** 进程级回退根：只建一次，失败渠道不再各自堆一个随机临时目录。 */
  private static volatile Path fallbackRoot;

  private InboundMediaRoots() {}

  /**
   * 返回该渠道的入站媒体目录（已创建）。
   *
   * @param workspaceRoot 工作区根的**本机执行视图**（{@code WorkspaceStorage.nativePath(root())}，042）；不得为 null
   * @param channelName 渠道名，只用作末级目录名（经 {@link InboundMediaPaths#safeSegment} 清理）
   * @param tempPrefix 回退目录前缀；调用方传入的值仅作末级目录名
   */
  public static Path forChannel(Path workspaceRoot, String channelName, String tempPrefix) {
    Objects.requireNonNull(workspaceRoot, "workspaceRoot");
    String channelSeg = safeSegment(channelName);
    Path preferred = workspaceRoot.resolve(INBOUND_MEDIA).resolve(channelSeg);
    try {
      Files.createDirectories(preferred);
      return preferred;
    } catch (IOException e) {
      LOG.warn(
          "创建入站媒体目录失败（{}），回退临时目录: {}", sanitize(preferred.toString()), sanitize(e.getMessage()));
      try {
        Path channelDir = fallbackRoot().resolve(channelSeg);
        Files.createDirectories(channelDir);
        return channelDir;
      } catch (IOException fallback) {
        Path last =
            Path.of(
                System.getProperty("java.io.tmpdir"),
                safeSegment(
                    tempPrefix == null || tempPrefix.isBlank() ? FALLBACK_TEMP_PREFIX : tempPrefix),
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

  private static Path fallbackRoot() throws IOException {
    Path root = fallbackRoot;
    if (root != null) {
      return root;
    }
    // 前缀用常量：避免 SpotBugs PATH_TRAVERSAL_IN（调用方传入的 tempPrefix 仅作末级目录名）
    synchronized (InboundMediaRoots.class) {
      if (fallbackRoot == null) {
        fallbackRoot = Files.createTempDirectory(FALLBACK_TEMP_PREFIX);
      }
      return fallbackRoot;
    }
  }

  static String safeSegment(String raw) {
    return InboundMediaPaths.safeSegment(raw);
  }

  private static String sanitize(String value) {
    // 内联替换：SpotBugs CRLF_INJECTION_LOGS 需在本类内可见的 \r/\n 清洗
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
