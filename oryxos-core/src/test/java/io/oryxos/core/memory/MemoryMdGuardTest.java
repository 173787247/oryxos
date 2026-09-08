package io.oryxos.core.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryMdGuardTest {

  @TempDir Path temp;

  @Test
  @DisplayName("叶子名为 MEMORY.md 时拒绝")
  void rejectsLeafMemoryMd() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MemoryMdGuard.rejectMutation(".oryxos/agents/demo/MEMORY.md"));
    assertThrows(
        IllegalArgumentException.class,
        () -> MemoryMdGuard.rejectMutation("agents/demo/memory.md"));
  }

  @Test
  @DisplayName("中间路径段为 MEMORY.md 时也拒绝（防建成目录）")
  void rejectsMemoryMdAsAncestorSegment() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MemoryMdGuard.rejectMutation(".oryxos/agents/demo/MEMORY.md/child.txt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> MemoryMdGuard.rejectMutation("agents/demo/Memory.md/nested/x.md"));
  }

  @Test
  @DisplayName("普通路径放行")
  void allowsOrdinaryPaths() {
    assertDoesNotThrow(() -> MemoryMdGuard.rejectMutation("agents/demo/notes.md"));
    assertDoesNotThrow(() -> MemoryMdGuard.rejectMutation("memory/backup.txt"));
    assertDoesNotThrow(() -> MemoryMdGuard.rejectMutation((String) null));
    assertDoesNotThrow(() -> MemoryMdGuard.rejectMutation("  "));
    assertDoesNotThrow(() -> MemoryMdGuard.rejectMutation((Path) null));
  }

  @Test
  @DisplayName("软链叶子指向 MEMORY.md 时拒绝（真实路径复检）")
  void rejectsSymlinkLeafToMemoryMd() throws IOException {
    Path memory = temp.resolve("MEMORY.md");
    Files.writeString(memory, "## 核心记忆\n## 归档记忆\n");
    Path alias = temp.resolve("notes.md");
    assumeCanSymlink(alias, memory);

    assertThrows(IllegalArgumentException.class, () -> MemoryMdGuard.rejectMutation(alias));
    assertThrows(
        IllegalArgumentException.class, () -> MemoryMdGuard.rejectMutation(alias.toString()));
    assertEqualsUnchanged(memory);
  }

  @Test
  @DisplayName("悬空软链目标词法为 MEMORY.md 时也拒绝")
  void rejectsDanglingSymlinkNamedMemoryMd() throws IOException {
    Path alias = temp.resolve("alias.md");
    assumeCanSymlink(alias, Path.of("MEMORY.md"));

    assertThrows(IllegalArgumentException.class, () -> MemoryMdGuard.rejectMutation(alias));
  }

  private static void assumeCanSymlink(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (IOException | UnsupportedOperationException e) {
      assumeTrue(false, "当前环境无法创建软链: " + e.getMessage());
    }
  }

  private static void assertEqualsUnchanged(Path memory) throws IOException {
    org.junit.jupiter.api.Assertions.assertEquals("## 核心记忆\n## 归档记忆\n", Files.readString(memory));
  }
}
