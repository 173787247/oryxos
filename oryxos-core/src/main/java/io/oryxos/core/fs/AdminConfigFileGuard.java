package io.oryxos.core.fs;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 管理台热更配置只能经 AdminService 落盘——文件工具 / Workspace / download_file 直写会绕过校验与断连重连。
 *
 * <p>覆盖 {@code channels.yaml}（{@code ChannelAdminService}）与 {@code mcp_servers.yaml}（{@code
 * McpServerAdminService}）。
 */
public final class AdminConfigFileGuard {

  private static final Set<String> RESERVED_LOWER = Set.of("channels.yaml", "mcp_servers.yaml");

  private AdminConfigFileGuard() {}

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "Reserved filenames are ASCII; Locale.ROOT fold matches case-insensitive filesystems.")
  public static void rejectMutation(String path) {
    if (path == null || path.isBlank()) {
      return;
    }
    // 任意路径段命中即可：write_file("…/channels.yaml/x") 会 createDirectories 把配置名建成目录
    for (Path segment : Path.of(path)) {
      String lower = segment.toString().toLowerCase(Locale.ROOT);
      if (RESERVED_LOWER.contains(lower)) {
        throw new IllegalArgumentException("拒绝直接改写管理配置（请用 Channel / MCP 管理入口）: " + path);
      }
    }
  }
}
