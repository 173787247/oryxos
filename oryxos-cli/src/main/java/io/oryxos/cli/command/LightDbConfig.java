package io.oryxos.cli.command;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 轻命令的 datasource 解析（025，T017）：不起 Spring，但与重命令读同一份 {@code config/application.yml} （相对
 * CWD，两边看到的必须是同一个库）。缺省/未配置 = 内置 SQLite 档 {@code oryxos.db}；配置为 PG url 时轻命令同口径直连（SQL
 * 已是标准语法两库通用）。密码支持 {@code ${ENV}} / {@code ${ENV:default}} 占位。
 */
final class LightDbConfig {

  private static final String DEFAULT_SQLITE_FILE = "oryxos.db";
  private static final String SQLITE_PREFIX = "jdbc:sqlite:";
  private static final String BUSY_TIMEOUT = "busy_timeout";
  private static final Path EXTERNAL_CONFIG = Path.of("config", "application.yml");
  private final String url;
  private final String username;
  private final String password;

  private LightDbConfig(String url, String username, String password) {
    this.url = url;
    this.username = username;
    this.password = password;
  }

  static LightDbConfig load() {
    Map<String, Object> datasource = readDatasourceSection();
    String url = resolvePlaceholders(stringValue(datasource.get("url")));
    if (url == null || url.isBlank()) {
      url = SQLITE_PREFIX + DEFAULT_SQLITE_FILE + "?" + BUSY_TIMEOUT + "=5000";
    } else if (url.startsWith(SQLITE_PREFIX) && !url.contains(BUSY_TIMEOUT)) {
      url = url + (url.contains("?") ? "&" : "?") + BUSY_TIMEOUT + "=5000";
    }
    return new LightDbConfig(
        url,
        resolvePlaceholders(stringValue(datasource.get("username"))),
        resolvePlaceholders(stringValue(datasource.get("password"))));
  }

  boolean isSqlite() {
    return url.startsWith(SQLITE_PREFIX);
  }

  /** SQLite 档专用：数据文件尚未生成（首次重命令运行时才建）。PG 档恒 false，状态由连接探测判定。 */
  boolean sqliteFileMissing() {
    return isSqlite() && !Files.exists(Path.of(sqliteFile()));
  }

  /** SQLite 数据文件相对路径（去掉 jdbc 前缀与连接参数）；仅 isSqlite() 时有意义。 */
  String sqliteFile() {
    String file = url.substring(SQLITE_PREFIX.length());
    int paramsAt = file.indexOf('?');
    return paramsAt >= 0 ? file.substring(0, paramsAt) : file;
  }

  /** 一句给用户看的库指向描述（不含凭证）。 */
  String describe() {
    return isSqlite() ? sqliteFile() : url;
  }

  Connection connect() throws SQLException {
    Properties props = new Properties();
    if (username != null && !username.isBlank()) {
      props.setProperty("user", username);
    }
    if (password != null && !password.isBlank()) {
      props.setProperty("password", password);
    }
    return DriverManager.getConnection(url, props);
  }

  private static Map<String, Object> readDatasourceSection() {
    if (!Files.isRegularFile(EXTERNAL_CONFIG)) {
      return Map.of();
    }
    try (InputStream in = Files.newInputStream(EXTERNAL_CONFIG)) {
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      Object root = yaml.load(in);
      Object spring = root instanceof Map<?, ?> map ? map.get("spring") : null;
      Object datasource = spring instanceof Map<?, ?> map ? map.get("datasource") : null;
      if (datasource instanceof Map<?, ?> map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) map;
        return typed;
      }
      return Map.of();
    } catch (IOException | RuntimeException e) {
      // 配置可读性问题交给重命令的 ConfigLoader 严格报错；轻命令按缺省档继续
      return Map.of();
    }
  }

  private static String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static String resolvePlaceholders(String value) {
    if (value == null) {
      return null;
    }
    StringBuilder resolved = new StringBuilder(value.length());
    int i = 0;
    while (i < value.length()) {
      int start = value.indexOf("${", i);
      if (start < 0) {
        resolved.append(value, i, value.length());
        break;
      }
      int end = value.indexOf('}', start + 2);
      if (end < 0) {
        resolved.append(value, i, value.length());
        break;
      }
      resolved.append(value, i, start);
      String token = value.substring(start + 2, end);
      int colon = token.indexOf(':');
      String name = colon < 0 ? token : token.substring(0, colon);
      String fallback = colon < 0 ? "" : token.substring(colon + 1);
      String env = System.getenv(name);
      resolved.append(env == null ? fallback : env);
      i = end + 1;
    }
    return resolved.toString();
  }
}
