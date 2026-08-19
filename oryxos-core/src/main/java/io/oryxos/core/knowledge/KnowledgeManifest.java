package io.oryxos.core.knowledge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * KNOWLEDGE.md 清单（FR-001/FR-015）：frontmatter 承载 name/description/backend 与远程连接引用。 只读
 * frontmatter，不读正文（库级说明不入索引）；连接引用里的 {@code ${ENV_VAR}} 占位原样保留， 由具体后端插件在使用时按环境变量解析——清单层绝不落明文凭证（宪法
 * VI）。
 *
 * @param name 库名，必须与目录名一致
 * @param description 描述（渐进披露注入的元数据）
 * @param backend 后端插件名，缺省 local
 * @param connection 远程后端连接引用（原样字符串），本地库为空 Map
 */
public record KnowledgeManifest(
    String name, String description, String backend, Map<String, String> connection) {

  /** 清单文件名。 */
  public static final String FILE = "KNOWLEDGE.md";

  private static final String FENCE = "---";

  public KnowledgeManifest {
    connection = connection == null ? Map.of() : Map.copyOf(connection);
  }

  /** 读取并校验一个知识库目录的清单；非法清单抛可读 {@link IllegalArgumentException}（US4 场景 3）。 */
  public static KnowledgeManifest read(Path kbDirectory) {
    Path file = kbDirectory.resolve(FILE);
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("知识库目录缺少 " + FILE + ": " + kbDirectory.getFileName());
    }
    Map<?, ?> map = frontmatter(file, kbDirectory);
    String name = value(map.get("name"));
    String description = value(map.get("description"));
    if (name.isBlank()) {
      throw new IllegalArgumentException(FILE + " 缺少 name: " + kbDirectory.getFileName());
    }
    if (description.isBlank()) {
      throw new IllegalArgumentException(FILE + " 缺少 description: " + kbDirectory.getFileName());
    }
    String directoryName = String.valueOf(kbDirectory.getFileName());
    if (!directoryName.equals(name)) {
      throw new IllegalArgumentException("知识库 name 与目录名不一致: " + name + " != " + directoryName);
    }
    String backend = value(map.get("backend"));
    if (backend.isBlank()) {
      backend = KnowledgeBackendRegistry.LOCAL;
    }
    return new KnowledgeManifest(name, description, backend, connectionOf(map.get("connection")));
  }

  private static Map<?, ?> frontmatter(Path file, Path kbDirectory) {
    try (BufferedReader reader = Files.newBufferedReader(file)) {
      if (!FENCE.equals(reader.readLine())) {
        throw new IllegalArgumentException(FILE + " 缺少 frontmatter: " + kbDirectory.getFileName());
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
        throw new IllegalArgumentException(FILE + " frontmatter 未闭合: " + kbDirectory.getFileName());
      }
      Object loaded = new Yaml().load(yaml.toString());
      if (!(loaded instanceof Map<?, ?> map)) {
        throw new IllegalArgumentException(
            FILE + " frontmatter 不是对象: " + kbDirectory.getFileName());
      }
      return map;
    } catch (IOException e) {
      throw new UncheckedIOException("读取 " + FILE + " 失败: " + file, e);
    }
  }

  private static Map<String, String> connectionOf(Object raw) {
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(FILE + " connection 必须是键值对象");
    }
    Map<String, String> connection = new LinkedHashMap<>();
    map.forEach((key, value) -> connection.put(String.valueOf(key), value(value)));
    return connection;
  }

  private static String value(Object value) {
    return value == null ? "" : String.valueOf(value).strip();
  }
}
