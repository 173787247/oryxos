package io.oryxos.cli.command;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

/** 轻命令：会话概览。纯 JDBC 只读查 sessions 表（与重命令同一相对路径 oryxos.db），零 Spring。 */
@Command(
    name = "session",
    description = "Session 相关操作",
    mixinStandardHelpOptions = true,
    subcommands = SessionListCommand.ListCommand.class)
public class SessionListCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  @Command(name = "list", description = "列出会话", mixinStandardHelpOptions = true)
  static class ListCommand implements Callable<Integer> {

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
      if (db.sqliteFileMissing()) {
        // 库还没建 = 还没有会话，不是失败：0
        out.println("暂无会话（" + db.sqliteFile() + " 尚未创建）。");
        return 0;
      }
      String sql =
          "SELECT session_id, profile_name, status, last_active_at FROM sessions"
              + " ORDER BY last_active_at DESC";
      try (Connection conn = db.connect();
          Statement stmt = conn.createStatement();
          ResultSet rs = stmt.executeQuery(sql)) {
        boolean any = false;
        while (rs.next()) {
          any = true;
          out.printf(
              "%-40s %-16s %-9s %s%n",
              rs.getString("session_id"),
              rs.getString("profile_name"),
              rs.getString("status"),
              rs.getString("last_active_at") == null ? "-" : rs.getString("last_active_at"));
        }
        if (!any) {
          out.println("暂无会话。");
        }
      } catch (SQLException e) {
        // 查询失败 = 失败：非零退出，脚本的 `|| echo failed` 才会触发
        err.println("查询会话失败: " + e.getMessage());
        return 1;
      }
      return 0;
    }
  }
}
