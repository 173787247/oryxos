package io.oryxos.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.oryxos.core.agent.AgencyAgentsImporter;
import io.oryxos.core.agent.AgencyAgentsParser;
import io.oryxos.core.agent.AgencyAgentsParser.ParsedExpert;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 12 个内置预设的导入产物快照（golden file）：parser/importer 或预设结构任何改动，若静默改变某个预设的导入结果，这里 diff
 * 即见——合成片段测试覆盖不到真实数据的回归（025 迁移：导入产物防腐化的落地验收）。
 *
 * <p>重新生成快照（改动预设、或确认行为变更后）：先人工 review {@code render} 的输出合理，再 {@code mvn -pl oryxos-core test
 * -Dtest=PersonaPresetsGoldenTest -Dpersona.golden.overwrite=true} 覆盖写快照，最后 review git diff 再提交。
 */
class PersonaPresetsGoldenTest {

  /** 系统属性开关：非空时覆盖写快照（供维护，不默认开启）。 */
  private static final String OVERWRITE = "persona.golden.overwrite";

  private static final Path GOLDEN_DIR = Path.of("src/test/resources/personas-golden");

  private final PersonaPresetCatalog catalog = new PersonaPresetCatalog();

  @Test
  @DisplayName("12 个预设导入产物与快照一致（防静默腐化）")
  void presets_importMatchesGolden() {
    List<PersonaPresetCatalog.Preset> presets = catalog.all();
    assertFalse(presets.isEmpty());
    for (PersonaPresetCatalog.Preset p : presets) {
      String actual = render(p);
      if (System.getProperty(OVERWRITE) != null) {
        writeGolden(p.key(), actual);
        continue;
      }
      assertEquals(
          readGolden(p.key()), actual, () -> presetDiff(p.key(), readGolden(p.key()), actual));
    }
  }

  /** 镜像 Web import-preview 的真实调用：解析 + 5 参导入（name = 预设 key、model 占位），工具集固定保证确定性。 */
  private String render(PersonaPresetCatalog.Preset p) {
    ParsedExpert expert = new AgencyAgentsParser().parse(catalog.sourceContent(p));
    return new AgencyAgentsImporter()
        .toMarkdown(expert, "deepseek", Set.of("read_file", "shell", "notify"), p.key(), null);
  }

  private static String readGolden(String key) {
    Path f = GOLDEN_DIR.resolve(key + ".md");
    try {
      return normalize(Files.readString(f, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("缺黄金快照: " + f + "（首次生成用 -Dpersona.golden.overwrite=true）", e);
    }
  }

  private static void writeGolden(String key, String content) {
    Path f = GOLDEN_DIR.resolve(key + ".md");
    try {
      Files.createDirectories(f.getParent());
      Files.writeString(f, normalize(content), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("写黄金快照失败: " + f, e);
    }
  }

  /** 快照统一 LF：.gitattributes 已强制 eol=lf，这里再兜底归一，防编辑器或个别环境把快照存成 CRLF 造成假性 diff。 */
  private static String normalize(String s) {
    return s.replace("\r\n", "\n");
  }

  private static String presetDiff(String key, String expected, String actual) {
    String[] e = expected.split("\n", -1);
    String[] a = actual.split("\n", -1);
    int n = Math.min(e.length, a.length);
    for (int i = 0; i < n; i++) {
      if (!e[i].equals(a[i])) {
        return key + " 第 " + (i + 1) + " 行不一致:\n  期望: " + e[i] + "\n  实际: " + a[i];
      }
    }
    return key + " 行数不一致: 期望 " + e.length + " 实际 " + a.length;
  }
}
