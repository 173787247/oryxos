package io.oryxos.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 人格库 store 切片：扁平 personas/<key>.md 的写/读/删/列 + key 校验（025人格库）。 */
class PersonaStoreTest {

  @TempDir Path root;

  private PersonaStore store() {
    return new PersonaStore(root);
  }

  @Test
  @DisplayName("写/读/列/存在：每个 key = personas/<key>.md")
  void writeReadListExists() {
    PersonaStore store = store();
    assertFalse(store.exists("code-reviewer"));
    assertEquals(List.of(), store.list());

    store.write("code-reviewer", "---\nname: 审查员\n---\n正文");

    assertTrue(store.exists("code-reviewer"));
    assertEquals("---\nname: 审查员\n---\n正文", store.read("code-reviewer"));
    assertEquals(List.of("code-reviewer"), store.list());
    assertTrue(Files.isRegularFile(root.resolve("personas/code-reviewer.md")));
  }

  @Test
  @DisplayName("覆盖同名自定义：同 key 再写即覆盖")
  void writeOverwrites() {
    PersonaStore store = store();
    store.write("x", "v1");
    store.write("x", "v2");
    assertEquals("v2", store.read("x"));
  }

  @Test
  @DisplayName("删除物理删文件（copy-in 库无反向引用）")
  void deleteRemovesFile() {
    PersonaStore store = store();
    store.write("x", "v");
    store.delete("x");
    assertFalse(store.exists("x"));
    assertFalse(Files.exists(root.resolve("personas/x.md")));
  }

  @Test
  @DisplayName("读不存在的 key 抛 IllegalStateException")
  void readMissing_throws() {
    assertThrows(IllegalStateException.class, () -> store().read("ghost"));
  }

  @Test
  @DisplayName("非法 key 拒绝：路径穿越 / 中文 / 空 / null")
  void illegalKey_rejected() {
    PersonaStore store = store();
    assertThrows(IllegalArgumentException.class, () -> store.write("../escape", "x"));
    assertThrows(IllegalArgumentException.class, () -> store.write("a/b", "x"));
    assertThrows(IllegalArgumentException.class, () -> store.write("中文", "x"));
    assertThrows(IllegalArgumentException.class, () -> store.write("", "x"));
    assertThrows(IllegalArgumentException.class, () -> store.write(null, "x"));
  }

  @Test
  @DisplayName("entryExists 检出目录/软链残留，不只看普通文件")
  void entryExists_detectsResidue() throws Exception {
    PersonaStore store = store();
    // 同名目录占住 <key>.md 这个键：entryExists 能看出占用，exists 判定不可读（非普通文件）
    Files.createDirectories(root.resolve("personas/residue.md"));
    assertTrue(store.entryExists("residue"));
    assertFalse(store.exists("residue"));
  }
}
