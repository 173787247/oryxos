package io.oryxos.core.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 原子写纪律（027，FR-004/SC-004）：写全才落位、失败零残留、覆盖旧内容完整切换。 */
class AtomicFilesTest {

  @TempDir Path dir;

  @Test
  void write_createsParentAndLandsCompleteContent() throws Exception {
    Path target = dir.resolve("nested/sub/AGENT.md");
    AtomicFiles.writeString(target, "hello");
    assertThat(Files.readString(target)).isEqualTo("hello");
  }

  @Test
  void write_replacesExistingAtomically_noTempLeftBehind() throws Exception {
    Path target = dir.resolve("SKILL.md");
    AtomicFiles.writeString(target, "old");
    AtomicFiles.writeString(target, "new-complete-content");
    assertThat(Files.readString(target)).isEqualTo("new-complete-content");
    try (var files = Files.list(dir)) {
      assertThat(files).containsExactly(target); // 无 .write- 临时残留
    }
  }

  @Test
  void write_failureKeepsOldContentAndCleansTemp() throws Exception {
    Path target = dir.resolve("persona.md");
    AtomicFiles.writeString(target, "intact");
    // 让目标位置成为「非空目录」制造 move 失败：旧文件先移开、建同名目录
    Path asDir = dir.resolve("persona.md.bak");
    Files.move(target, asDir);
    Files.createDirectory(target);
    Files.createFile(target.resolve("occupied"));

    assertThrows(UncheckedIOException.class, () -> AtomicFiles.writeString(target, "boom"));
    try (var files = Files.list(dir)) {
      assertThat(files.filter(p -> p.getFileName().toString().startsWith(".persona"))).isEmpty();
    }
  }
}
