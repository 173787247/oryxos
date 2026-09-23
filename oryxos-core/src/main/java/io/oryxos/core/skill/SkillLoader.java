package io.oryxos.core.skill;

import io.oryxos.core.agent.AgentMarkdown;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动时扫描 {@code .oryxos/skills/} 下每个子目录的 {@code SKILL.md}，派生成 {@link Skill} 建立索引（第 32 节）。
 *
 * <p>与 {@code AgentLoader} 同构：单个坏目录记 ERROR 跳过、不阻断其余加载。frontmatter 用 {@link AgentMarkdown#split}
 * 拆分（复用同一套 frontmatter/正文解析）。name 与 description 都是渐进式目录所需元数据，必须显式非空； 从目录加载时 name 还必须等于目录名。
 */
public class SkillLoader {

  private static final Logger LOG = LoggerFactory.getLogger(SkillLoader.class);
  private static final String SKILL_FILE = "SKILL.md";

  /** SKILL.md 整文件上限（与 AgentLoader / ContextLoader 的 10 MiB 对齐）。 */
  static final long MAX_SKILL_MD_BYTES = 10L * 1024 * 1024;

  private final Path skillsDir;

  public SkillLoader(Path skillsDir) {
    this.skillsDir = skillsDir;
  }

  /** 扫描目录并返回加载成功的 Skill 索引；单目录失败只记日志。 */
  public SkillRegistry loadAll() {
    Map<String, Skill> loaded = new LinkedHashMap<>();
    if (!existingDirectory(skillsDir, true)) {
      LOG.warn("Skill 目录不存在，跳过加载: {}", sanitize(skillsDir.toString()));
      return new SkillRegistry(loaded);
    }
    try (Stream<Path> dirs = Files.list(skillsDir)) {
      dirs.filter(path -> existingDirectory(path, false))
          .sorted()
          .forEach(
              dir -> {
                try {
                  Skill skill = deriveSkill(dir);
                  loaded.put(skill.name(), skill);
                } catch (UncheckedIOException unavailable) {
                  throw unavailable;
                } catch (RuntimeException e) {
                  existingDirectory(skillsDir, true);
                  LOG.error(
                      "跳过损坏的 Skill 目录 {}: {}",
                      sanitize(String.valueOf(dir.getFileName())),
                      sanitize(e.getMessage()));
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException("扫描 Skill 目录失败", e);
    }
    return new SkillRegistry(loaded);
  }

  private static boolean existingDirectory(Path path, boolean followLinks) {
    try {
      LinkOption[] options =
          followLinks ? new LinkOption[0] : new LinkOption[] {LinkOption.NOFOLLOW_LINKS};
      return Files.readAttributes(path, BasicFileAttributes.class, options).isDirectory();
    } catch (NoSuchFileException missing) {
      return false;
    } catch (IOException failure) {
      throw new UncheckedIOException("读取 Skill 目录项失败: " + path, failure);
    }
  }

  /** 读 {@code <dir>/SKILL.md} 派生 Skill；缺文件、元数据不完整或 name 与目录不一致均抛异常。 */
  public Skill deriveSkill(Path skillDir) {
    try {
      if (!skillDir.toRealPath().startsWith(skillsDir.toRealPath())) {
        throw new IllegalArgumentException("Skill 目录真实路径越界: " + skillDir.getFileName());
      }
    } catch (NoSuchFileException missing) {
      throw new IllegalArgumentException("Skill 目录不存在: " + skillDir.getFileName(), missing);
    } catch (IOException failure) {
      throw new UncheckedIOException("解析 Skill 目录真实路径失败: " + skillDir, failure);
    }
    Path skillMd = skillDir.resolve(SKILL_FILE);
    BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(skillMd, BasicFileAttributes.class);
    } catch (NoSuchFileException missing) {
      throw new IllegalArgumentException("Skill 目录缺少 SKILL.md: " + skillDir.getFileName());
    } catch (IOException failure) {
      throw new UncheckedIOException("读取 SKILL.md 属性失败: " + skillMd, failure);
    }
    if (!attributes.isRegularFile()) {
      throw new IllegalArgumentException("Skill 目录缺少 SKILL.md: " + skillDir.getFileName());
    }
    long size = attributes.size();
    if (size > MAX_SKILL_MD_BYTES) {
      throw new IllegalArgumentException(
          "SKILL.md 过大（"
              + size
              + " bytes），上限 "
              + MAX_SKILL_MD_BYTES
              + " bytes（10 MiB）: "
              + skillDir.getFileName());
    }
    String dirName = String.valueOf(skillDir.getFileName());
    Skill skill = parse(read(skillMd), dirName);
    if (!dirName.equals(skill.name())) {
      throw new IllegalArgumentException("Skill name 与目录名不一致: " + skill.name() + " != " + dirName);
    }
    return skill;
  }

  /** 把一份 SKILL.md 文本解析成 Skill；fallbackName 仅用于报错定位，不再替代必填 name。 */
  public Skill parse(String markdown, String fallbackName) {
    AgentMarkdown.Parsed parsed = AgentMarkdown.split(markdown);
    Object nameVal = parsed.frontmatter().get("name");
    if (!(nameVal instanceof String rawName)) {
      throw new IllegalArgumentException("SKILL.md 缺少 name: " + fallbackName);
    }
    String name = rawName.strip();
    if (name.isBlank()) {
      throw new IllegalArgumentException("SKILL.md 缺少 name: " + fallbackName);
    }
    Object descVal = parsed.frontmatter().get("description");
    String description = descVal == null ? null : String.valueOf(descVal).strip();
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("SKILL.md 缺少 description: " + fallbackName);
    }
    if (parsed.body().isBlank()) {
      throw new IllegalArgumentException("SKILL.md 正文为空: " + fallbackName);
    }
    return new Skill(name, description, parsed.body());
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("读取 SKILL.md 失败: " + file.getFileName(), e);
    }
  }

  /** 日志参数消毒：去掉换行，防日志伪造（CRLF injection）。 */
  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
