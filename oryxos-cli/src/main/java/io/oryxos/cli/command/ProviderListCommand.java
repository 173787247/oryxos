package io.oryxos.cli.command;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

/**
 * Provider 相关操作。
 *
 * <p>{@code list} 读的是运行时同一个 Provider 注册表（{@code providers} 表）：YAML 只在服务首次启动时播种，之后数据库是唯一事实源 （{@code
 * OryxOsRuntime#providerRegistry}）。直读打包 {@code application.yml} 会漏掉管理台新增的 provider，也会一直显示已经删掉的。
 */
@Command(
    name = "provider",
    description = "Provider 相关操作",
    mixinStandardHelpOptions = true,
    subcommands = ProviderListCommand.ListCommand.class)
public class ProviderListCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  /**
   * 轻命令：纯 JDBC 只读查 {@code providers} 表（与 {@code session list} 同一条 {@link LightDbConfig} 通道，零
   * Spring）。 返回码：0 = 查询成功（含空注册表）；1 = 数据库不可用（未初始化 / 连接失败 / 缺表），绝不回落到读打包 YAML。
   */
  @Command(
      name = "list",
      description = "列出 Provider 注册表（数据库，唯一事实源）",
      mixinStandardHelpOptions = true)
  static class ListCommand implements Callable<Integer> {

    private static final String QUERY =
        "SELECT name, base_url, description, api_key FROM providers ORDER BY name";
    private static final String MOCK = "mock";
    private static final int NAME_WIDTH = 20;
    private static final int DEFAULT_WIDTH = 8;
    private static final int BASE_URL_WIDTH = 52;
    private static final String FOOTNOTE = "default(*) 为运行时派生（注册表内按大小写无关排序的最小名），不是库字段。";

    private final LightDbConfig db;
    private final PrintStream out;
    private final PrintStream err;

    /** picocli 走这个构造：与重命令读同一份 {@code config/application.yml}（025）。 */
    ListCommand() {
      this(LightDbConfig.load(), System.out, System.err);
    }

    /** 单测缝：注入库指向与输出流，测试不碰真实 config/application.yml、不改全局 System.out。 */
    ListCommand(LightDbConfig db, PrintStream out, PrintStream err) {
      this.db = db;
      this.out = out;
      this.err = err;
    }

    @Override
    public Integer call() {
      // 必须先判文件缺失再 connect：SQLite 连不存在的路径会顺手建出空库，只读命令不该有这个副作用。
      if (db.sqliteFileMissing()) {
        err.println(
            "Provider 注册表不可用：数据库尚未初始化（"
                + db.describe()
                + " 不存在）。先运行 `oryxos serve` 完成首次播种，或检查 config/application.yml 的"
                + " spring.datasource.url。");
        return 1;
      }
      List<ProviderRow> rows = new ArrayList<>();
      try (Connection conn = db.connect();
          Statement stmt = conn.createStatement();
          ResultSet rs = stmt.executeQuery(QUERY)) {
        while (rs.next()) {
          rows.add(
              new ProviderRow(
                  rs.getString("name"),
                  rs.getString("base_url"),
                  rs.getString("description"),
                  rs.getString("api_key")));
        }
      } catch (SQLException e) {
        err.println("查询 Provider 注册表失败（" + db.describe() + "）：" + e.getMessage());
        if (e.getMessage() != null && e.getMessage().contains("no such table")) {
          err.println("数据库尚未迁移到含 providers 表的版本？先运行 `oryxos serve` 完成迁移与首次播种。");
        }
        return 1;
      }
      if (rows.isEmpty()) {
        out.println("Provider 注册表为空（" + db.describe() + "）。用管理台或 POST /api/v1/providers 添加。");
        return 0;
      }
      String defaultName =
          rows.stream()
              .map(ProviderRow::name)
              .filter(name -> name != null && !name.isBlank())
              .min(String::compareToIgnoreCase)
              .orElse(null);
      out.println("Provider 注册表（" + db.describe() + "）：");
      out.printf(
          "%-" + NAME_WIDTH + "s %-" + DEFAULT_WIDTH + "s %-" + BASE_URL_WIDTH + "s %s%n",
          "NAME",
          "DEFAULT",
          "BASE-URL",
          "DESCRIPTION");
      for (ProviderRow row : rows) {
        out.printf(
            "%-" + NAME_WIDTH + "s %-" + DEFAULT_WIDTH + "s %-" + BASE_URL_WIDTH + "s %s%n",
            row.name(),
            row.name() == null || !row.name().equals(defaultName) ? "" : "*",
            blankToDash(row.baseUrl()),
            blankTo(row.description()));
      }
      out.println(FOOTNOTE);
      warnMissingApiKeys(rows);
      return 0;
    }

    /** api_key 只做存在性判断（022 起库里是密文，只打印"有没有"，任何片段都不输出）。 */
    private void warnMissingApiKeys(List<ProviderRow> rows) {
      List<String> missing = new ArrayList<>();
      for (ProviderRow row : rows) {
        if (!MOCK.equals(row.name()) && (row.apiKey() == null || row.apiKey().isBlank())) {
          missing.add(row.name());
        }
      }
      if (!missing.isEmpty()) {
        err.println("注意：以下 provider 未配置 api_key：" + String.join(", ", missing));
      }
    }

    private static String blankToDash(String value) {
      return value == null || value.isBlank() ? "-" : value;
    }

    private static String blankTo(String value) {
      return value == null ? "" : value;
    }

    /** 一行注册表数据；apiKey 只用于存在性判断，从不打印。 */
    private record ProviderRow(String name, String baseUrl, String description, String apiKey) {}
  }
}
