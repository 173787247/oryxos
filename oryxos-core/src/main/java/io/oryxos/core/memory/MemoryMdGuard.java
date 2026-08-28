package io.oryxos.core.memory;

import io.oryxos.core.fs.RealPathBoundary;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 长期记忆文件只能经 {@code save_memory} 写入——{@code MarkdownMemoryStore#sanitizeEntryContent}
 * 只覆盖那条路径；其它入口直写会绕过分区头消毒（#163），污染核心/归档召回。
 *
 * <p>除词法路径段检查外，还会经 {@link RealPathBoundary} 解析已存在祖先的真实路径，避免 {@code notes.md → MEMORY.md} 这类软链绕过。
 */
public final class MemoryMdGuard {

  /** 小写文件名；比较时用 {@link Locale#ROOT}。 */
  private static final String MEMORY_FILE_LOWER = "memory.md";

  private MemoryMdGuard() {}

  public static void rejectMutation(String path) {
    if (path == null || path.isBlank()) {
      return;
    }
    rejectLexical(path);
    rejectResolved(Path.of(path));
  }

  /** 对已解析（常为绝对）路径做词法 + 真实路径双重检查；Workspace 等入口应在边界投影后再调。 */
  public static void rejectMutation(Path path) {
    if (path == null) {
      return;
    }
    rejectLexical(path.toString());
    rejectResolved(path);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "文件名是 ASCII「MEMORY.md」；Locale.ROOT 大小写折叠仅用于 Windows 大小写不敏感路径，非安全边界上的任意 Unicode 文本")
  private static void rejectLexical(String path) {
    if (path == null || path.isBlank()) {
      return;
    }
    // 任意路径段命中即可：write_file("…/MEMORY.md/x.txt") 会 createDirectories 把 MEMORY.md 建成目录
    for (Path segment : Path.of(path)) {
      if (MEMORY_FILE_LOWER.equals(segment.toString().toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException("拒绝直接改写 MEMORY.md，请使用 save_memory: " + path);
      }
    }
  }

  private static void rejectResolved(Path path) {
    // 悬空软链：project 可能失败，先按叶子链接目标做词法检查
    rejectSymlinkLeafTarget(path);
    Path projected;
    try {
      projected = RealPathBoundary.project(path).projectedReal();
    } catch (UncheckedIOException | IllegalArgumentException e) {
      return;
    }
    rejectLexical(projected.toString());
  }

  /** 叶子是软链时，目标字符串及其相对父目录解析结果也做词法检查（覆盖悬空链）。 */
  private static void rejectSymlinkLeafTarget(Path path) {
    Path absolute = path.toAbsolutePath().normalize();
    if (!Files.isSymbolicLink(absolute)) {
      return;
    }
    try {
      Path linkTarget = Files.readSymbolicLink(absolute);
      rejectLexical(linkTarget.toString());
      Path parent = absolute.getParent();
      if (parent != null) {
        rejectLexical(parent.resolve(linkTarget).normalize().toString());
      }
    } catch (IOException ignored) {
      // 读链失败则保守放行词法已通过的路径；真实写入仍受沙箱约束
    }
  }
}
