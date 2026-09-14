package io.oryxos.core.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 工作区原子写工具（027，FR-004）：同目录临时文件写全 + ATOMIC_MOVE 改名落位——共享卷上绝无半写文件，
 * 其他副本读到的要么是旧完整内容要么是新完整内容。文件系统不支持原子移动时抛异常而非降级 （沿 AgentStore.moveAtomic 的「不降级」策略：静默降级等于放弃承诺）。
 *
 * <p>正确性不依赖文件锁（宪法 VI + 共享卷支持矩阵：只依赖读写可见性 + 同卷 rename 原子性）。
 */
public final class AtomicFiles {

  private AtomicFiles() {}

  /** 原子写字节内容：父目录不存在时先创建。 */
  public static void write(Path target, byte[] content) {
    Path temp = tempSibling(target);
    try {
      Path parent = target.toAbsolutePath().getParent();
      if (parent == null) {
        throw new IOException("目标路径缺少父目录: " + target);
      }
      Files.createDirectories(parent);
      Files.write(temp, content);
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      deleteQuietly(temp);
      throw new UncheckedIOException("原子写入失败: " + target, e);
    }
  }

  /** 原子写字符串内容（UTF-8）。 */
  public static void writeString(Path target, String content) {
    write(target, content.getBytes(StandardCharsets.UTF_8));
  }

  private static Path tempSibling(Path target) {
    Path abs = target.toAbsolutePath();
    return abs.resolveSibling("." + abs.getFileName() + ".write-" + UUID.randomUUID());
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // 清理临时文件失败不掩盖主异常
    }
  }
}
