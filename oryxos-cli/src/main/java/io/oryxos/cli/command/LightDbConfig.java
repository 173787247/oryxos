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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
  private static final Path EXTERNAL_CONFIG = Path.of("config", "application.yml");
  private static final Pattern ENV_PLACEHOLDER =
      Pattern.compile("\\$\\{([A-Za-z0-9_]+)(?::([^}]*))?}");

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
      url = "jdbc:sqlite:" + DEFAULT_SQLITE_FILE + "?busy_timeout=5000";
    } else if (url.startsWith("jdbc:sqlite:") && !url.contains("busy_timeout")) {
      url = url + (url.contains("?") ? "&" : "?") + "busy_timeout=5000";
    }
    return new LightDbConfig(
        url,
        resolvePlaceholders(stringValue(datasource.get("username"))),
        resolvePlaceholders(stringValue(datasource.get("password"))));
  }

  boolean isSqlite() {
    return url.startsWith("jdbc:sqlite:");
  }

  /** SQLite 档专用：数据文件尚未生成（首次重命令运行时才建）。PG 档恒 false，状态由连接探测判定。 */
  boolean sqliteFileMissing() {
    return isSqlite() && !Files.exists(Path.of(sqliteFile()));
  }

  /** SQLite 数据文件相对路径（去掉 jdbc 前缀与连接参数）；仅 isSqlite() 时有意义。 */
  String sqliteFile() {
    String file = url.substring("jdbc:sqlite:".length());
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
    Matcher matcher = ENV_PLACEHOLDER.matcher(value);
    StringBuilder resolved = new StringBuilder();
    while (matcher.find()) {
      String env = System.getenv(matcher.group(1));
      String fallback = matcher.group(2) == null ? "" : matcher.group(2);
      matcher.appendReplacement(resolved, Matcher.quoteReplacement(env == null ? fallback : env));
    }
    matcher.appendTail(resolved);
    return resolved.toString();
  }
}
