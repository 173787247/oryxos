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
import org.springframework.util.PlaceholderResolutionException;
import org.springframework.util.PropertyPlaceholderHelper;
import org.springframework.util.SystemPropertyUtils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/**
 * 轻命令的 datasource 解析（025，T017）：不起 Spring，但与重命令读同一份 {@code config/application.yml} （相对
 * CWD，两边看到的必须是同一个库）。缺省/未配置 = 内置 SQLite 档 {@code oryxos.db}；配置为 PG url 时轻命令同口径直连（SQL 已是标准语法两库通用）。
 *
 * <p>占位符沿用 Spring 自己的 {@link PropertyPlaceholderHelper}（Spring Framework 7.0.9，与 Spring Boot
 * 绑定用的是同一个类与同一套 参数）：取值源是<b>系统属性优先、环境变量其次</b>；支持 {@code ${ENV:default}}、嵌套、{@code \${literal}}
 * 转义与默认值递归。 <b>已知与重命令的差异</b>：拿不到 Spring Environment，所以配置文件内的自引用属性与命令行参数 （{@code
 * --spring.datasource.url=...}）不作为占位符来源；这些值会按"未定义"处理。
 *
 * <p><b>比 Boot 绑定更严（fail-closed）</b>：Boot 的 {@code PropertySourcesPlaceholdersResolver} 用 {@code
 * ignoreUnresolvablePlaceholders=true}，未解析的占位符会原样作为字面量绑定（{@code ${MISSING}} 会成为字面量 {@code
 * "${MISSING}"}，启动不失败、连接时才炸）；轻命令用 {@code false}，缺失且无默认值直接抛 {@link
 * LightDbConfigException}——这是唯一能提前说清楚的路径，也避免拿空串或字面量凭证去连接。
 *
 * <p>失败口径：外部配置<b>文件不存在</b>时回落内置 SQLite 档；<b>文件存在但读不出 / 解析失败 / 结构非法</b>时抛 {@link
 * LightDbConfigException}，绝不静默换成另一个库——否则轻命令会安静地读"另一个库"的数据，而重命令同一份配置是启动失败。
 */
final class LightDbConfig {

  private static final String DEFAULT_SQLITE_FILE = "oryxos.db";
  private static final String SQLITE_PREFIX = "jdbc:sqlite:";
  private static final String BUSY_TIMEOUT = "busy_timeout";
  private static final Path EXTERNAL_CONFIG = Path.of("config", "application.yml");

  /**
   * 与 Spring Boot 的 {@code PropertySourcesPlaceholdersResolver} 同参：前缀/后缀/默认值分隔符/转义符完全一致，只把 {@code
   * ignoreUnresolvablePlaceholders} 设为 {@code false} —— 缺失且无默认值时抛 {@link
   * PlaceholderResolutionException}，再由 {@link #resolvePlaceholders} 折成 {@link
   * LightDbConfigException}。
   *
   * <p>注意本类<b>不 trim</b> 占位符名：与 Spring 7.0.9 实测一致（{@code ${ DB_PASS }} 交给解析器的名字是 {@code " DB_PASS
   * "}， Boot 绑定会原样保留为字面量），所以 {@code ${ DB_PASS }} <b>不</b>会因为环境里有 {@code DB_PASS} 而解析。
   */
  private static final PropertyPlaceholderHelper PLACEHOLDERS =
      new PropertyPlaceholderHelper("${", "}", ":", SystemPropertyUtils.ESCAPE_CHARACTER, false);

  private final String url;
  private final String username;
  private final String password;

  private LightDbConfig(String url, String username, String password) {
    this.url = url;
    this.username = username;
    this.password = password;
  }

  static LightDbConfig load() {
    return load(EXTERNAL_CONFIG);
  }

  /** 从指定配置文件解析（生产走 CWD 下的 {@code config/application.yml}；用例可指向临时文件）。 */
  static LightDbConfig load(Path configFile) {
    Map<String, Object> datasource = readDatasourceSection(configFile);
    String url =
        resolvePlaceholders(
            stringValue(datasource.get("url")), "spring.datasource.url", configFile);
    if (url != null && url.isBlank()) {
      // 有 url 键但解析后为空（如 url: ${DB_URL:}）：与"显式空 url"同口径——它会把重命令的打包默认覆盖成空，必须报错。
      throw new LightDbConfigException(
          configFile + " 的 spring.datasource.url 解析后为空，轻命令无法确定数据库；拒绝回落到内置 SQLite。");
    }
    if (url == null) {
      url = SQLITE_PREFIX + DEFAULT_SQLITE_FILE + "?" + BUSY_TIMEOUT + "=5000";
    } else if (url.startsWith(SQLITE_PREFIX) && !url.contains(BUSY_TIMEOUT)) {
      url = url + (url.contains("?") ? "&" : "?") + BUSY_TIMEOUT + "=5000";
    }
    return new LightDbConfig(
        url,
        resolvePlaceholders(
            stringValue(datasource.get("username")), "spring.datasource.username", configFile),
        resolvePlaceholders(
            stringValue(datasource.get("password")), "spring.datasource.password", configFile));
  }

  /**
   * 单测缝：直接指定 url（跳过 {@code config/application.yml} 解析），让轻命令用例指向临时库；同 {@code
   * KnowledgeWatcher.newWatchService()} 的注入思路。
   */
  static LightDbConfig forUrl(String url) {
    return new LightDbConfig(url, null, null);
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

  private static Map<String, Object> readDatasourceSection(Path configFile) {
    if (!Files.isRegularFile(configFile)) {
      return Map.of(); // 外部配置本就不存在：重命令此时也用打包默认 url，两边同一个库
    }
    Object root = loadYaml(configFile);
    if (root == null) {
      return Map.of(); // 空文件：没有 datasource 覆盖，沿用内置默认
    }
    if (!(root instanceof Map<?, ?> rootMap)) {
      throw new LightDbConfigException(configFile + " 顶层不是映射（键值结构），轻命令无法确定数据库；拒绝回落到内置 SQLite。");
    }
    Map<?, ?> springMap;
    Object spring = rootMap.get("spring");
    if (spring == null) {
      springMap = null;
    } else if (spring instanceof Map<?, ?> springValue) {
      springMap = springValue;
    } else {
      throw new LightDbConfigException(
          configFile + " 的 spring 段不是映射（键值结构），轻命令无法确定数据库；拒绝回落到内置 SQLite。");
    }
    Object datasource = springMap == null ? null : springMap.get("datasource");
    if (datasource == null) {
      return Map.of(); // 没写 datasource：重命令沿用打包默认，与内置 SQLite 档同一个库
    }
    if (!(datasource instanceof Map<?, ?> datasourceMap)) {
      throw new LightDbConfigException(
          configFile + " 的 spring.datasource 不是映射（键值结构），轻命令无法确定数据库；拒绝回落到内置 SQLite。");
    }
    Object url = datasourceMap.get("url");
    if (url != null && !(url instanceof String stringUrl && !stringUrl.isBlank())) {
      throw new LightDbConfigException(
          configFile + " 的 spring.datasource.url 不是非空字符串，轻命令无法确定数据库；拒绝回落到内置 SQLite。");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> typed = (Map<String, Object>) datasourceMap;
    return typed;
  }

  private static Object loadYaml(Path configFile) {
    try (InputStream in = Files.newInputStream(configFile)) {
      return new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
    } catch (IOException e) {
      throw new LightDbConfigException(
          "读取 " + configFile + " 失败：" + e.getMessage() + "；轻命令无法确定数据库，拒绝回落到内置 SQLite。", e);
    } catch (RuntimeException e) {
      throw new LightDbConfigException(describeYamlError(configFile, e), e);
    }
  }

  /** 把 SnakeYAML 的定位信息折成一行：文件 + 第几行第几列 + problem（有 Mark 时）。 */
  private static String describeYamlError(Path configFile, RuntimeException e) {
    if (e instanceof MarkedYAMLException marked
        && marked.getProblemMark() != null
        && marked.getProblemMark().getLine() >= 0) {
      return "解析 "
          + configFile
          + " 失败（第 "
          + (marked.getProblemMark().getLine() + 1)
          + " 行第 "
          + (marked.getProblemMark().getColumn() + 1)
          + " 列）："
          + marked.getProblem()
          + "；轻命令无法确定数据库，拒绝回落到内置 SQLite。";
    }
    return "解析 " + configFile + " 失败：" + e.getMessage() + "；轻命令无法确定数据库，拒绝回落到内置 SQLite。";
  }

  private static String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /**
   * 解析占位符。失败时只用 {@link PlaceholderResolutionException#getPlaceholder()} 点名缺失项——Spring 的原始消息带着整段
   * value，可能含凭证，绝不透出。
   */
  private static String resolvePlaceholders(String value, String key, Path configFile) {
    if (value == null) {
      return null;
    }
    try {
      return PLACEHOLDERS.replacePlaceholders(value, LightDbConfig::lookupProperty);
    } catch (PlaceholderResolutionException e) {
      throw new LightDbConfigException(
          configFile
              + " 的 "
              + key
              + " 引用了未定义的占位符 ${"
              + e.getPlaceholder()
              + "}（无默认值）；轻命令拒绝以空值或字面量连接数据库。"
              + "设置对应环境变量或 -D 系统属性后重试。",
          e);
    }
  }

  /** 取值源与 Spring Environment 的两档对齐：系统属性优先，其次环境变量；其余来源见类注释的已知差异。 */
  private static String lookupProperty(String name) {
    String fromSystemProperty = System.getProperty(name);
    return fromSystemProperty != null ? fromSystemProperty : System.getenv(name);
  }
}
