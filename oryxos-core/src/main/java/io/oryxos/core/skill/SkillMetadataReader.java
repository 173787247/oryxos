package io.oryxos.core.skill;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** Reads only SKILL.md frontmatter while verifying that a non-empty body boundary exists. */
public final class SkillMetadataReader {

  private static final String FENCE = "---";

  /** 与 SkillLoader 对齐的 SKILL.md 上限；绑定/巡检读前拒超限文件。 */
  static final long MAX_SKILL_MD_BYTES = 10L * 1024 * 1024;

  public Metadata read(Path skillDirectory) {
    Path file = skillDirectory.resolve("SKILL.md");
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("Skill 目录缺少可读 SKILL.md: " + skillDirectory.getFileName());
    }
    try {
      long size = Files.size(file);
      if (size > MAX_SKILL_MD_BYTES) {
        throw new IllegalArgumentException(
            "SKILL.md 过大（"
                + size
                + " bytes），上限 "
                + MAX_SKILL_MD_BYTES
                + " bytes（10 MiB）: "
                + skillDirectory.getFileName());
      }
    } catch (IOException e) {
      throw new UncheckedIOException("读取 SKILL.md 大小失败: " + file, e);
    }
    try (BufferedReader reader = Files.newBufferedReader(file)) {
      if (!FENCE.equals(reader.readLine())) {
        throw new IllegalArgumentException(
            "SKILL.md 缺少 frontmatter: " + skillDirectory.getFileName());
      }
      StringBuilder yaml = new StringBuilder();
      String line;
      boolean closed = false;
      while ((line = reader.readLine()) != null) {
        if (FENCE.equals(line.strip())) {
          closed = true;
          break;
        }
        yaml.append(line).append('\n');
      }
      if (!closed) {
        throw new IllegalArgumentException(
            "SKILL.md frontmatter 未闭合: " + skillDirectory.getFileName());
      }
      boolean hasBody = false;
      while ((line = reader.readLine()) != null) {
        if (!line.isBlank()) {
          hasBody = true;
          break;
        }
      }
      if (!hasBody) {
        throw new IllegalArgumentException("SKILL.md 正文为空: " + skillDirectory.getFileName());
      }
      Object loaded = new Yaml().load(yaml.toString());
      if (!(loaded instanceof Map<?, ?> map)) {
        throw new IllegalArgumentException(
            "SKILL.md frontmatter 不是对象: " + skillDirectory.getFileName());
      }
      String name = value(map.get("name"));
      String description = value(map.get("description"));
      if (name.isBlank()) {
        throw new IllegalArgumentException("SKILL.md 缺少 name: " + skillDirectory.getFileName());
      }
      if (description.isBlank()) {
        throw new IllegalArgumentException(
            "SKILL.md 缺少 description: " + skillDirectory.getFileName());
      }
      String directoryName = String.valueOf(skillDirectory.getFileName());
      if (!directoryName.equals(name)) {
        throw new IllegalArgumentException("Skill name 与目录名不一致: " + name + " != " + directoryName);
      }
      return new Metadata(name, description);
    } catch (IOException e) {
      throw new UncheckedIOException("读取 SKILL.md 元数据失败: " + file, e);
    }
  }

  private static String value(Object value) {
    return value == null ? "" : String.valueOf(value).strip();
  }

  public record Metadata(String name, String description) {}
}
