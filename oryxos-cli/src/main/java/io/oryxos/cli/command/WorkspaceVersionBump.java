package io.oryxos.cli.command;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * 轻命令的版本号总线写端（027）：文件落盘/归档成功后，直写 {@code workspace_versions} 对应域。
 *
 * <p>轻命令刻意不起 Spring，所以不走 {@code CoordinationStore}/{@code WorkspaceVersionNotifier}，而是复用轻命令既有的
 * {@link LightDbConfig} 原生 JDBC 通道。写得的是同一张表、同一条语义：{@code version = version + 1}，{@code updated_by}
 * 记写者，{@code updated_at} 记时刻。
 *
 * <p><b>与 JPA 侧各写一份 SQL</b>：{@code io.oryxos.storage.JpaCoordinationStore#bumpWorkspaceVersion}
 * （{@code WorkspaceVersionRepository#bump}）是另一份。两处必须同步改；{@code
 * io.oryxos.boot.WorkspaceVersionBumpCrosscheckTest} 把两侧接到同一个库上互认，任何一侧 SQL/列名漂移都会红。
 *
 * <p>失败纪律镜像 {@link io.oryxos.core.cluster.WorkspaceVersionNotifier}：通知失败不得影响写路径主链路——文件已经
 * 落盘/归档，这里只告警并给出可执行的兜底，不让命令报错。
 */
public class WorkspaceVersionBump {

  /** 与 {@code WorkspaceVersionRepository#bump} 同一语义；改这里必须同步 storage 侧（反之亦然）。 */
  private static final String BUMP_SQL =
      "UPDATE workspace_versions"
          + " SET version = version + 1, updated_by = ?, updated_at = ?"
          + " WHERE domain = ?";

  private static final String CLI_WRITER = "cli";

  /** 递增某域版本号（生产路径）。SQLite 数据文件尚未创建时静默跳过——总线还没初始化，没有消费者；连接/表/写入失败只告警， 因为此时文件改动已经生效，报错只会让用户以为命令失败。 */
  public void bump(String domain) {
    try (Connection conn = connect()) {
      if (conn == null) {
        return; // SQLite 数据文件尚未创建：轻命令从未与库打过交道，没有需要通知的副本
      }
      bump(conn, domain);
    } catch (SQLException | RuntimeException e) {
      System.err.println(
          "工作区版本号递增失败，集群档副本可能未感知本次变更（"
              + e.getMessage()
              + "）；可稍后 POST /api/v1/workspace/refresh，或重启副本兜底。");
    }
  }

  /**
   * 打开版本号总线连接；返回 {@code null} 表示总线尚未初始化（SQLite 数据文件不存在），调用方静默跳过。 包级可覆盖：用例指向临时库， 不依赖进程 CWD 下的 {@code
   * config/application.yml}。
   */
  protected Connection connect() throws SQLException {
    LightDbConfig db = LightDbConfig.load();
    return db.sqliteFileMissing() ? null : db.connect();
  }

  /**
   * 单条自增 SQL（与 JPA 侧同表同语义）。返回受影响行数；跨模块交叉用例用它直接验证两侧互认。
   *
   * @param conn 已连接的 {@code workspace_versions} 所在库
   * @param domain 工作区域（{@code agents}/{@code skills}/{@code personas}/{@code knowledge}）
   */
  public static int bump(Connection conn, String domain) throws SQLException {
    try (PreparedStatement stmt = conn.prepareStatement(BUMP_SQL)) {
      stmt.setString(1, CLI_WRITER);
      stmt.setTimestamp(2, Timestamp.from(Instant.now()));
      stmt.setString(3, domain);
      return stmt.executeUpdate();
    }
  }
}
