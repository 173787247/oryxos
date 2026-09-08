package io.oryxos.cli.command;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
  static class ListCommand implements Runnable {

    @Override
    public void run() {
      // 与重命令读同一份 config/application.yml——两边看到的必须是同一个库（025：SQLite 或 PG）
      LightDbConfig db = LightDbConfig.load();
      if (db.sqliteFileMissing()) {
        System.out.println("暂无会话（" + db.sqliteFile() + " 尚未创建）。");
        return;
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
          System.out.printf(
              "%-40s %-16s %-9s %s%n",
              rs.getString("session_id"),
              rs.getString("profile_name"),
              rs.getString("status"),
              rs.getString("last_active_at") == null ? "-" : rs.getString("last_active_at"));
        }
        if (!any) {
          System.out.println("暂无会话。");
        }
      } catch (SQLException e) {
        System.err.println("查询会话失败: " + e.getMessage());
      }
    }
  }
}
