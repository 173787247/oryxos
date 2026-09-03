package io.oryxos.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.AgencyAgentsImporter;
import io.oryxos.core.agent.AgencyAgentsParser;
import io.oryxos.core.agent.AgencyAgentsParser.ParsedExpert;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PersonaPresetCatalog：12 个默认预设随 jar 内置，源文件可被 AgencyAgentsParser 解析成合格人格。 */
class PersonaPresetCatalogTest {

  private final PersonaPresetCatalog catalog = new PersonaPresetCatalog();

  @Test
  @DisplayName("all：恰好 12 个预设，元数据齐全（label/description/sourceFile 非空）")
  void all_hasTwelvePresetsWithCompleteMeta() {
    List<PersonaPresetCatalog.Preset> presets = catalog.all();
    assertEquals(12, presets.size(), "默认预设应恰好 12 个（025 §12 决策表）");
    for (PersonaPresetCatalog.Preset p : presets) {
      assertFalse(p.key().isBlank(), "key 非空");
      assertFalse(p.label().isBlank(), "label 非空");
      assertFalse(p.description().isBlank(), "description 非空（取源 frontmatter 或 label 兜底）");
      assertFalse(p.sourceFile().isBlank(), "sourceFile 非空（署名必须）");
      assertFalse(p.emoji().isBlank(), "emoji 非空（源文件 frontmatter 都有）");
    }
  }

  @Test
  @DisplayName("get：按 key 命中；未知 key 返回 empty")
  void get_findsKnownKeyAndEmptyForUnknown() {
    assertTrue(catalog.get("product-manager").isPresent(), "已知 key 应命中");
    assertEquals("产品经理", catalog.get("product-manager").orElseThrow().label());
    assertEquals(Optional.empty(), catalog.get("no-such-preset"), "未知 key 返回 empty");
  }

  @Test
  @DisplayName("sourceContent：源文件可被解析 + 渲染成含 persona 的合格 AGENT.md；散文体身份段内容进 background 不丢")
  void sourceContent_parsesAndRendersIntoQualifiedAgent() {
    for (PersonaPresetCatalog.Preset p : catalog.all()) {
      String content = catalog.sourceContent(p);
      assertFalse(content.isBlank(), "源文件非空: " + p.key());
      ParsedExpert expert = new AgencyAgentsParser().parse(content);
      assertNotNull(expert.displayName(), "frontmatter name 解析出 displayName: " + p.key());
      assertFalse(expert.body().isBlank(), "任务层正文非空: " + p.key());
      // 结构化标签行（- 角色：/- 个性：）存在才提得出 role/traits；散文体身份段（如 product-manager）内容进 background，绝不丢
      assertFalse(
          expert.role().isBlank() && expert.background().isBlank(),
          "role 或 background 至少一个有内容: " + p.key());
      // 端到端：每个预设都要能渲染成含 persona 段的 AGENT.md（Web 导入的最终产物）
      String rendered =
          new AgencyAgentsImporter().toMarkdown(expert, "deepseek", java.util.Set.of());
      assertTrue(rendered.contains("persona:"), "渲染产物含 persona 段: " + p.key());
      assertTrue(rendered.contains("role:"), "渲染产物含 persona.role: " + p.key());
    }
  }

  @Test
  @DisplayName("未知 key 的 sourceContent 抛 IllegalStateException（资源缺失不静默）")
  void sourceContent_unknownPresetThrows() {
    PersonaPresetCatalog.Preset unknown =
        new PersonaPresetCatalog.Preset("no-such", "x", "x", "x", "x");
    assertThrows(IllegalStateException.class, () -> catalog.sourceContent(unknown));
  }
}
