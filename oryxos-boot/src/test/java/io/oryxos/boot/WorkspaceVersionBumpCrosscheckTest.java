package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.cli.command.WorkspaceVersionBump;
import io.oryxos.core.cluster.CoordinationStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 两处 bump SQL 的交叉验证：CLI 轻命令的 {@link WorkspaceVersionBump}（零 Spring、原生 JDBC）与 JPA 的 {@link
 * CoordinationStore#bumpWorkspaceVersion}（{@code WorkspaceVersionRepository}）各写一份、指向同一张 {@code
 * workspace_versions} 表。本用例把两侧接到同一个库上互认——任一侧 SQL/列名漂移都会红，避免这份重复悄悄分叉。
 */
class WorkspaceVersionBumpCrosscheckTest {

  private static Path root;
  private static String url;
  private static ConfigurableApplicationContext ctx;
  private static CoordinationStore coordination;

  @BeforeAll
  static void boot() throws Exception {
    root = Files.createTempDirectory("oryxos-version-bump-crosscheck");
    url = "jdbc:sqlite:" + root.resolve("oryxos.db");
    ctx =
        new SpringApplicationBuilder(OryxOsRuntime.class)
            .run(
                "--oryxos.root=" + root,
                "--oryxos.providers[0].name=mock",
                "--memory.backend=sqlite",
                "--spring.datasource.url=" + url,
                "--spring.lifecycle.timeout-per-shutdown-phase=100ms",
                "--spring.main.web-application-type=none");
    coordination = ctx.getBean(CoordinationStore.class);
  }

  @AfterAll
  static void close() {
    if (ctx != null) {
      ctx.close();
    }
  }

  @Test
  @DisplayName("CLI 写端与 JPA bumpWorkspaceVersion 互认同一个 workspace_versions 行")
  void cliWriterAndJpaWriterAgree() throws SQLException {
    long baseline = coordination.workspaceVersions().get("agents");

    try (Connection conn = DriverManager.getConnection(url)) {
      assertEquals(1, WorkspaceVersionBump.bump(conn, "agents"), "CLI 写端必须命中 agents 行");
    }
    assertEquals(
        baseline + 1,
        coordination.workspaceVersions().get("agents"),
        "JPA 侧必须读到 CLI 写的版本号（同一张表、同一语义）");

    coordination.bumpWorkspaceVersion("agents", "jpa-crosscheck");
    try (Connection conn = DriverManager.getConnection(url)) {
      assertEquals(baseline + 2, readVersion(conn, "agents"), "CLI 侧必须读到 JPA 写的版本号（反向同样成立）");
    }
  }

  private static long readVersion(Connection conn, String domain) throws SQLException {
    try (PreparedStatement stmt =
        conn.prepareStatement("SELECT version FROM workspace_versions WHERE domain = ?")) {
      stmt.setString(1, domain);
      try (ResultSet rs = stmt.executeQuery()) {
        if (!rs.next()) {
          throw new AssertionError("workspace_versions 缺少域: " + domain);
        }
        return rs.getLong(1);
      }
    }
  }
}
