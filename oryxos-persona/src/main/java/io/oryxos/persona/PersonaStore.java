package io.oryxos.persona;

import io.oryxos.core.fs.RealPathBoundary;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 自定义人格库的文件读写，限定在 {@code .oryxos/personas/} 内（025「人格库」阶段二落地）。
 *
 * <p>扁平结构：每个 key = 一个 {@code personas/<key>.md} 文件（内容为 persona 源文件：frontmatter + 正文，与内置预设同构）。 删除 =
 * **物理删除**（persona 无反向引用：库里的人不挂在 Agent 上，Agent 只保存复制进来的内容——copy-in 模板库，非按名引用）。
 *
 * <p>key 必须是安全文件名段（只允许字母/数字/下划线/连字符，防路径穿越），与 {@code AgentStore}/{@code SkillStore} 同口径。
 */
public class PersonaStore {

  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]+");
  private static final String PERSONA_DIR = "personas";
  private static final String MARKDOWN_SUFFIX = ".md";

  private final Path personasDir;

  public PersonaStore(Path oryxosRoot) {
    this.personasDir = oryxosRoot.toAbsolutePath().normalize().resolve(PERSONA_DIR);
  }

  /** 该 key 的自定义人格是否存在（{@code personas/<key>.md} 是普通文件）。 */
  public boolean exists(String key) {
    Path file = file(key);
    return RealPathBoundary.isWithin(personasDir, file) && Files.isRegularFile(file);
  }

  /** 读自定义人格源文件全文；缺文件抛 {@link IllegalStateException}（调用方应先确认存在）。 */
  public String read(String key) {
    Path file = file(key);
    if (!Files.isRegularFile(file)) {
      throw new IllegalStateException("自定义人格不存在: " + key);
    }
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("读取自定义人格失败: " + key, e);
    }
  }

  /** 写 {@code personas/<key>.md}（覆盖已有同名自定义）。 */
  public void write(String key, String content) {
    Path file = file(key);
    try {
      Files.createDirectories(personasDir);
      Files.writeString(file, content);
    } catch (IOException e) {
      throw new UncheckedIOException("写入自定义人格失败: " + key, e);
    }
  }

  /** 物理删除 {@code personas/<key>.md}（copy-in 库无反向引用，直接删文件即可）。 */
  public void delete(String key) {
    Path file = file(key);
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      throw new UncheckedIOException("删除自定义人格失败: " + key, e);
    }
  }

  /** 扫 {@code personas/*.md} 返回全部自定义 key（= 文件名 stem，字典序）。 */
  public List<String> list() {
    if (!Files.isDirectory(personasDir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(personasDir)) {
      return files
          .filter(Files::isRegularFile)
          .map(PersonaStore::stem)
          .filter(name -> name != null && !name.isEmpty())
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("扫描自定义人格目录失败", e);
    }
  }

  private Path file(String key) {
    Path file = personasDir.resolve(safe(key) + MARKDOWN_SUFFIX);
    requireSafe(file);
    return file;
  }

  private void requireSafe(Path path) {
    RealPathBoundary.requireWithin(personasDir, path);
  }

  private static String stem(Path file) {
    String name = file.getFileName().toString();
    if (!name.endsWith(MARKDOWN_SUFFIX)) {
      return null;
    }
    return name.substring(0, name.length() - MARKDOWN_SUFFIX.length());
  }

  /** key 校验：只允许字母/数字/下划线/连字符（与 AgentStore.safe / SkillStore.safe 同口径，防路径穿越）。 */
  static String safe(String key) {
    if (key == null || !SAFE_NAME.matcher(key).matches()) {
      throw new IllegalArgumentException("非法人格 key（只允许字母/数字/下划线/连字符）: " + key);
    }
    return key;
  }

  /** 检查一个 key 名是否已被占用（自定义库内）；返回 false 时 {@link #safe} 会先抛非法 key。 */
  boolean entryExists(String key) {
    Path file = file(key);
    return Files.exists(file, LinkOption.NOFOLLOW_LINKS);
  }
}
