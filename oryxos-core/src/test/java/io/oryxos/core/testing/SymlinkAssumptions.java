package io.oryxos.core.testing;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试环境能力探针：Windows 非管理员且未开启开发者模式时，{@code Files.createSymbolicLink} 会抛
 * FileSystemException（「客户端没有所需的特权」），且 Windows 文件系统没有 POSIX 权限 API。
 *
 * <p>依赖这两项能力的用例在不支持的环境里通过 JUnit assumption 优雅跳过（标记 skipped 而非 error）， Linux CI 全量运行，避免平台差异造成假红。本类随
 * core test-jar 发布，供各模块测试复用。
 */
public final class SymlinkAssumptions {

  private SymlinkAssumptions() {}

  /**
   * 探测 {@code dir} 下能否实际创建符号链接（建一条临时探针链接后立即删除）；不能则中断当前测试。
   *
   * <p>用于链接由生产代码（绑定服务、迁移服务等）在调用栈深处创建、测试无法逐点 try/catch 的场景。
   */
  public static void assumeSymlinksSupported(Path dir) {
    Path probeDir = null;
    try {
      probeDir = Files.createTempDirectory(dir, "symlink-probe-");
      Path target = Files.createFile(probeDir.resolve("target"));
      Files.createSymbolicLink(probeDir.resolve("link"), target);
    } catch (IOException | UnsupportedOperationException e) {
      assumeTrue(false, "当前环境不支持创建符号链接，跳过该用例: " + e.getMessage());
    } finally {
      if (probeDir != null) {
        deleteQuietly(probeDir.resolve("link"));
        deleteQuietly(probeDir.resolve("target"));
        deleteQuietly(probeDir);
      }
    }
  }

  /**
   * 创建符号链接；环境不支持时中断当前测试（标记 skipped）。替代测试里直接调 {@code Files.createSymbolicLink} 后让 IOException 冒泡成
   * error 的写法。
   */
  public static void createSymbolicLinkOrAssume(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (IOException | UnsupportedOperationException e) {
      assumeTrue(false, "当前环境无法创建符号链接，跳过该用例: " + e.getMessage());
    }
  }

  /** POSIX 文件权限 API（{@code Files.getPosixFilePermissions} 等）仅类 Unix 可用；不支持则跳过。 */
  public static void assumePosixSupported() {
    assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "当前文件系统不支持 POSIX 权限 API，跳过该用例");
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // 探针清理失败无所谓，@TempDir 会统一回收
    }
  }
}
