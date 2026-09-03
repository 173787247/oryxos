package io.oryxos.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.persona.PersonaService.PersonaEntry;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 人格库服务切片：内置+自定义合并列表 / 内置只读 / 自定义 CRUD / key 冲突（025人格库）。 */
class PersonaServiceTest {

  @TempDir Path root;

  private PersonaService service() {
    return new PersonaService(new PersonaPresetCatalog(), new PersonaStore(root));
  }

  private static final String CUSTOM_MD =
      "---\nname: 团队审查员\ndescription: 团队定制人格\nemoji: 👀\n---\n正文";

  @Test
  @DisplayName("list 合并：12 内置（builtin=true）+ 自定义（builtin=false，带 frontmatter meta）")
  void list_mergesBuiltinsAndCustoms() {
    PersonaService s = service();
    s.create("team-reviewer", CUSTOM_MD);

    List<PersonaEntry> list = s.list();
    assertEquals(13, list.size());

    PersonaEntry builtin = list.get(0);
    assertTrue(builtin.builtin());
    assertTrue(builtin.sourceFile() != null); // 内置带署名来源

    PersonaEntry custom = list.get(12);
    assertFalse(custom.builtin());
    assertEquals("team-reviewer", custom.key());
    assertEquals("团队审查员", custom.label());
    assertEquals("团队定制人格", custom.description());
    assertNull(custom.sourceFile());
  }

  @Test
  @DisplayName("create：与内置同名冲突拒绝")
  void create_builtinKeyConflict_rejected() {
    PersonaService s = service();
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> s.create("product-manager", CUSTOM_MD));
    assertTrue(ex.getMessage().contains("内置人格只读"));
  }

  @Test
  @DisplayName("create：与已有自定义同名冲突拒绝")
  void create_duplicateCustom_rejected() {
    PersonaService s = service();
    s.create("a", CUSTOM_MD);
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> s.create("a", CUSTOM_MD));
    assertTrue(ex.getMessage().contains("已存在"));
  }

  @Test
  @DisplayName("create：空内容 / 非法 key 拒绝")
  void create_badInput_rejected() {
    PersonaService s = service();
    assertThrows(IllegalArgumentException.class, () -> s.create("a", "  "));
    assertThrows(IllegalArgumentException.class, () -> s.create("a", null));
    assertThrows(IllegalArgumentException.class, () -> s.create("../evil", "x"));
  }

  @Test
  @DisplayName("update：内置只读拒绝；自定义可改且重新投影 meta")
  void update_builtinReadonly_customOk() {
    PersonaService s = service();
    assertThrows(IllegalArgumentException.class, () -> s.update("product-manager", "x"));

    s.create("a", CUSTOM_MD);
    s.update("a", "---\nname: 新名字\n---\n新正文");
    Optional<PersonaEntry> updated = s.get("a");
    assertTrue(updated.isPresent());
    assertEquals("新名字", updated.orElseThrow().label());
    assertTrue(s.source("a").orElse("").contains("新正文"));
  }

  @Test
  @DisplayName("delete：内置只读拒绝；自定义物理删（source 回落到无）")
  void delete_builtinReadonly_customOk() {
    PersonaService s = service();
    assertThrows(IllegalArgumentException.class, () -> s.delete("product-manager"));

    s.create("a", CUSTOM_MD);
    s.delete("a");
    assertTrue(s.get("a").isEmpty());
    assertFalse(s.source("a").isPresent());
  }

  @Test
  @DisplayName("delete：不存在的自定义拒绝")
  void delete_unknown_rejected() {
    PersonaService s = service();
    assertThrows(IllegalArgumentException.class, () -> s.delete("no-such"));
  }

  @Test
  @DisplayName("source：自定义优先，回落到内置源文件原文")
  void source_customPreferred_thenBuiltin() {
    PersonaService s = service();
    assertTrue(s.source("product-manager").isPresent());
    assertFalse(s.source("product-manager").orElse("").isBlank());

    s.create("team-reviewer", CUSTOM_MD);
    assertEquals(CUSTOM_MD, s.source("team-reviewer").orElse(""));
  }
}
