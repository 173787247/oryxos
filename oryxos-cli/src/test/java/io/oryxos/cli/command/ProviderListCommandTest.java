package io.oryxos.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code oryxos provider list} 的来源是运行时同一个 Provider 注册表（providers 表），不是打包 application.yml。
 *
 * <p>旧实现对 classpath {@code application.yml} 做 SnakeYAML 直读：管理台新增/删除的 provider、外部 config 里声明的
 * provider 全看不见。这里用临时 SQLite 覆盖「查得到 / 删掉后不再列出 / 库不可用非零退出 / 不回落 yaml」。
 */
class ProviderListCommandTest {

  /** 与 db/migration/{sqlite,postgresql}/V1__baseline.sql 的 providers 表同构。 */
  private static final String DDL =
      """
      CREATE TABLE IF NOT EXISTS providers (
          name VARCHAR(128) PRIMARY KEY,
          api_key TEXT,
          base_url TEXT,
          description TEXT,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL
      )
      """;

  @TempDir Path tmp;

  @Test
  @DisplayName("DB 里 3 个 provider_全部列出并标出派生默认")
  void listsAllProvidersFromRegistry() throws Exception {
    Path dbFile = tmp.resolve("oryxos.db");
    try (Connection conn = open(dbFile)) {
      createTable(conn);
      insert(conn, "deepseek", "enc:v1:cipher-deepseek", "https://api.deepseek.com", "默认 DeepSeek");
      insert(conn, "mock", null, null, "内置假模型");
      insert(conn, "qwen", null, "https://dashscope.aliyuncs.com/compatible-mode/v1", "通义");
    }

    Result result = run(dbFile);

    assertEquals(0, result.code(), "查询成功应退出 0");
    assertTrue(result.out().contains("Provider 注册表（"), "应写明来源是 Provider 注册表");
    assertTrue(result.out().contains("deepseek"));
    assertTrue(result.out().contains("mock"));
    assertTrue(result.out().contains("qwen"));
    assertTrue(result.out().contains("https://api.deepseek.com"), "应列出 base_url");
    assertTrue(result.out().contains("通义"), "应列出 description");
    // default 是运行时派生值（大小写无关最小名），不是库字段
    assertTrue(lineStartingWith(result.out(), "deepseek").contains("*"), "最小名应标默认");
    assertFalse(lineStartingWith(result.out(), "qwen").contains("*"), "非最小名不标默认");
    assertTrue(result.out().contains("不是库字段"), "脚注应说明 default 为派生");
    // api_key 只判存在性：deepseek 有 key 不告警，qwen 缺 key 告警，mock 免 key 不告警
    assertTrue(result.err().contains("qwen"), "缺 key 的非 mock provider 应提示");
    assertFalse(result.err().contains("deepseek"), "已配置 key 的不应提示");
    assertFalse(result.err().contains("mock"), "mock 免 key 不应提示");
    // 任何 key 片段都不打印（022 起库里是密文，打印既无意义又可能泄露）
    assertFalse(result.out().contains("cipher-deepseek"), "不得打印 api_key");
    assertFalse(result.err().contains("cipher-deepseek"), "不得打印 api_key");
  }

  @Test
  @DisplayName("DB 里删掉某 provider 后不再列出")
  void deletedProviderDisappears() throws Exception {
    Path dbFile = tmp.resolve("oryxos.db");
    try (Connection conn = open(dbFile)) {
      createTable(conn);
      insert(conn, "deepseek", "enc:v1:cipher-deepseek", "https://api.deepseek.com", "默认 DeepSeek");
      insert(conn, "qwen", "enc:v1:cipher-qwen", "https://dashscope.example/v1", "通义");
    }
    assertTrue(run(dbFile).out().contains("qwen"), "前提：删除前 qwen 在列表里");

    try (Connection conn = open(dbFile);
        Statement stmt = conn.createStatement()) {
      stmt.execute("DELETE FROM providers WHERE name = 'qwen'");
    }

    Result result = run(dbFile);

    assertEquals(0, result.code());
    assertTrue(result.out().contains("deepseek"));
    assertFalse(result.out().contains("qwen"), "已删除的 provider 不得再列出");
  }

  @Test
  @DisplayName("数据库不可用_非零退出并写明指哪个库_不回落读 yaml")
  void unavailableDatabaseExitsNonZero() throws Exception {
    Path broken = tmp.resolve("broken.db");
    Files.writeString(broken, "this is not a sqlite database");

    Result result = run(broken);

    assertEquals(1, result.code(), "库不可用必须非零退出");
    assertTrue(result.err().contains("查询 Provider 注册表失败"), "应明确报错");
    assertTrue(result.err().contains(broken.toString()), "应写明查的是哪个库");
    assertFalse(result.out().contains("deepseek"), "不得静默回落到打包 yaml 的 provider 清单");
  }

  @Test
  @DisplayName("库存在但缺 providers 表_非零退出并提示迁移")
  void missingTableExitsNonZero() throws Exception {
    Path dbFile = tmp.resolve("no-table.db");
    try (Connection conn = open(dbFile);
        Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE other (id INTEGER PRIMARY KEY)");
    }

    Result result = run(dbFile);

    assertEquals(1, result.code(), "缺表必须非零退出");
    assertTrue(result.err().contains("查询 Provider 注册表失败"), "应明确报错");
    assertTrue(result.err().contains("providers"), "错误信息应点名 providers 表");
    assertTrue(result.err().contains("oryxos serve"), "应指路先启动服务完成迁移");
  }

  @Test
  @DisplayName("空注册表_退出 0 且明说为空")
  void emptyRegistryExitsZero() throws Exception {
    Path dbFile = tmp.resolve("empty.db");
    try (Connection conn = open(dbFile)) {
      createTable(conn);
    }

    Result result = run(dbFile);

    assertEquals(0, result.code(), "空注册表是合法状态，不应报错");
    assertTrue(result.out().contains("Provider 注册表为空"), "应明说为空");
  }

  @Test
  @DisplayName("SQLite 文件不存在_非零退出且不创建库文件（只读无副作用）")
  void missingDatabaseFileExitsNonZeroWithoutCreatingFile() throws Exception {
    Path dbFile = tmp.resolve("never-created.db");

    Result result = run(dbFile);

    assertEquals(1, result.code(), "库未初始化必须非零退出");
    assertTrue(result.err().contains("尚未初始化"), "应明说库还没初始化");
    assertTrue(result.err().contains("oryxos serve"), "应指路先启动服务完成播种");
    assertFalse(Files.exists(dbFile), "只读命令不得顺手建出空库文件");
  }

  private Result run(Path dbFile) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
    PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
    int code =
        new ProviderListCommand.ListCommand(
                LightDbConfig.forUrl("jdbc:sqlite:" + dbFile), outStream, errStream)
            .call();
    outStream.flush();
    errStream.flush();
    return new Result(
        code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private static Connection open(Path dbFile) throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
  }

  private static void createTable(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute(DDL);
    }
  }

  private static void insert(
      Connection conn, String name, String apiKey, String baseUrl, String description)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO providers (name, api_key, base_url, description, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
      ps.setString(1, name);
      ps.setString(2, apiKey);
      ps.setString(3, baseUrl);
      ps.setString(4, description);
      ps.executeUpdate();
    }
  }

  private static String lineStartingWith(String text, String prefix) {
    return text.lines()
        .filter(line -> line.startsWith(prefix))
        .findFirst()
        .orElseThrow(() -> new AssertionError("输出里没有以 " + prefix + " 开头的行:\n" + text));
  }

  private record Result(int code, String out, String err) {}
}
