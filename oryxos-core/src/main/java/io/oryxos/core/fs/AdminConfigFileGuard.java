package io.oryxos.core.fs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 系统保留文件守卫：管理台热更配置与 SQLite 数据库只能经专用管理入口读写——文件工具 / Workspace 直写会绕过校验与断连重连，通用读入口（read_file / grep /
 * workspace file·download）直接吐原文会泄露 channels.yaml / mcp_servers.yaml 里的渠道与 MCP 凭证、oryxos.db
 * 里的全量持久化数据。
 *
 * <p>覆盖 {@code channels.yaml}（{@code ChannelAdminService}）、{@code mcp_servers.yaml}（{@code
 * McpServerAdminService}）与 {@code oryxos.db} 家族（含 SQLite 侧车 {@code -wal}/{@code -shm}/ {@code
 * -journal} 与备份副本——同源数据，同样敏感）。
 *
 * <p>除词法路径段检查外，还会经 {@link RealPathBoundary} 解析已存在祖先的真实路径，避免 {@code alias.yaml → channels.yaml}
 * 这类软链绕过（对齐 {@code MemoryMdGuard}）。
 */
public final class AdminConfigFileGuard {

  private static final Set<String> RESERVED_CONFIG_LOWER =
      Set.of("channels.yaml", "mcp_servers.yaml");

  /** SQLite 主库文件名（小写）；侧车/备份以 {@code oryxos.db-} / {@code oryxos.db.} 起头。 */
  private static final String DB_FILE_LOWER = "oryxos.db";

  private AdminConfigFileGuard() {}

  // —— 写侧：既有入口，语义不变（保留集合扩展到 DB 家族）——

  public static void rejectMutation(String path) {
    if (path == null || path.isBlank()) {
      return;
    }
    if (hitsReserved(Path.of(path))) {
      throw new IllegalArgumentException("拒绝直接改写系统保留文件（管理配置 / 数据库请走 Channel、MCP 等对应管理入口）: " + path);
    }
  }

  /** 对已解析（常为绝对）路径做词法 + 真实路径双重检查；Workspace 等入口应在边界投影后再调。 */
  public static void rejectMutation(Path path) {
    if (path == null) {
      return;
    }
    if (hitsReserved(path)) {
      throw new IllegalArgumentException("拒绝直接改写系统保留文件（管理配置 / 数据库请走 Channel、MCP 等对应管理入口）: " + path);
    }
  }

  // —— 读侧：通用文件读入口（workspace file/download、read_file、copy_file 源、grep 遍历）——

  public static void rejectRead(String path) {
    if (path == null || path.isBlank()) {
      return;
    }
    if (hitsReserved(Path.of(path))) {
      throw new IllegalArgumentException(
          "拒绝直接读取系统保留文件原文（管理配置含凭证、oryxos.db 含全量数据，请走对应管理入口）: " + path);
    }
  }

  /** 对已解析（常为绝对）路径做词法 + 真实路径双重检查；Workspace 等入口应在边界投影后再调。 */
  public static void rejectRead(Path path) {
    if (path == null) {
      return;
    }
    if (hitsReserved(path)) {
      throw new IllegalArgumentException(
          "拒绝直接读取系统保留文件原文（管理配置含凭证、oryxos.db 含全量数据，请走对应管理入口）: " + path);
    }
  }

  /**
   * 读侧保留文件判定（不抛异常）：grep/glob 这类批量遍历入口据此跳过保留文件，而不是让整次搜索失败。 与 {@link #rejectRead(Path)} 共用同一套词法 +
   * 软链叶子 + 真实路径投影判定，不会出现口径分叉。
   */
  public static boolean isReservedRead(Path path) {
    return hitsReserved(path);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "Reserved filenames are ASCII; Locale.ROOT fold matches case-insensitive filesystems.")
  private static boolean hitsReserved(Path path) {
    if (path == null) {
      return false;
    }
    if (matchesReserved(path.toString())) {
      return true;
    }
    if (symlinkLeafHitsReserved(path)) {
      return true;
    }
    try {
      Path projected = RealPathBoundary.project(path).projectedReal();
      return matchesReserved(projected.toString());
    } catch (UncheckedIOException | IllegalArgumentException e) {
      // 真实路径无法解析（悬空链/链接环/IO 失败）：词法与叶子已过，保守放行——边界仍由沙箱/Workspace 投影兜底
      return false;
    }
  }

  /** 任意路径段命中保留名即可：写侧防 {@code …/channels.yaml/x} 把配置名建成目录，读侧同款段扫描。 */
  private static boolean matchesReserved(String path) {
    if (path == null || path.isBlank()) {
      return false;
    }
    for (Path segment : Path.of(path)) {
      String lower = segment.toString().toLowerCase(Locale.ROOT);
      if (RESERVED_CONFIG_LOWER.contains(lower) || isDbFamily(lower)) {
        return true;
      }
    }
    return false;
  }

  /** oryxos.db 主库与 SQLite 侧车/备份（-wal/-shm/-journal、.bak 等）同源数据，一并保留。 */
  private static boolean isDbFamily(String lowerSegment) {
    return DB_FILE_LOWER.equals(lowerSegment)
        || lowerSegment.startsWith(DB_FILE_LOWER + "-")
        || lowerSegment.startsWith(DB_FILE_LOWER + ".");
  }

  private static boolean symlinkLeafHitsReserved(Path path) {
    Path absolute = path.toAbsolutePath().normalize();
    if (!Files.isSymbolicLink(absolute)) {
      return false;
    }
    try {
      Path linkTarget = Files.readSymbolicLink(absolute);
      if (matchesReserved(linkTarget.toString())) {
        return true;
      }
      Path parent = absolute.getParent();
      return parent != null && matchesReserved(parent.resolve(linkTarget).normalize().toString());
    } catch (IOException e) {
      // 读链失败则保守放行词法已通过的路径；真实读取仍受沙箱/边界投影约束
      return false;
    }
  }
}
