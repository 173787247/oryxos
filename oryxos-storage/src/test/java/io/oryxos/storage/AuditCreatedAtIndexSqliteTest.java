package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * #1010 的 follow-up：审计看板按 {@code created_at} 过滤窗口，两张 append-only 审计表此前没有该索引， 每次看板加载都是全表扫描。V27 用纯
 * SQL 迁移补上复合索引。
 *
 * <p>SQLite 档：查 {@code sqlite_master} / {@code PRAGMA index_info}，并用 {@code EXPLAIN QUERY PLAN} 断言
 * #1010 的列表查询与聚合查询确实能走该索引（SQLite 的计划对空表也稳定，见迁移注释里的实测）。
 */
@SqliteJpaTest
class AuditCreatedAtIndexSqliteTest {

  @TempDir static Path dbDir;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("test.db"));
  }

  @Autowired private JdbcTemplate jdbc;

  @Test
  @DisplayName("V27 建出 (created_at, id) 复合索引，两表列序与迁移一致")
  void migrationCreatesCompositeIndexes() {
    assertThat(indexNames("llm_calls")).contains("idx_llm_calls_created_at_id");
    assertThat(indexNames("tool_invocations")).contains("idx_tool_invocations_created_at_id");
    assertThat(indexColumns("idx_llm_calls_created_at_id")).containsExactly("created_at", "id");
    assertThat(indexColumns("idx_tool_invocations_created_at_id"))
        .containsExactly("created_at", "id");
  }

  @Test
  @DisplayName("EXPLAIN QUERY PLAN：窗口列表与聚合查询都走该索引，列表无 temp B-tree 排序")
  void plannerUsesTheIndexForWindowQueries() {
    List<String> listPlan =
        explain(
            "SELECT id FROM llm_calls WHERE created_at BETWEEN ? AND ?"
                + " ORDER BY created_at DESC, id DESC LIMIT 10");
    assertThat(listPlan).anyMatch(line -> line.contains("idx_llm_calls_created_at_id"));
    assertThat(listPlan).noneMatch(line -> line.contains("TEMP B-TREE"));

    List<String> aggregatePlan =
        explain(
            "SELECT count(*), sum(duration_ms) FROM llm_calls WHERE created_at BETWEEN ? AND ?");
    assertThat(aggregatePlan).anyMatch(line -> line.contains("idx_llm_calls_created_at_id"));

    List<String> toolPlan =
        explain(
            "SELECT id FROM tool_invocations WHERE created_at BETWEEN ? AND ?"
                + " ORDER BY created_at DESC, id DESC LIMIT 10");
    assertThat(toolPlan).anyMatch(line -> line.contains("idx_tool_invocations_created_at_id"));
  }

  @Test
  @DisplayName("V27 脚本可重复执行：IF NOT EXISTS 让已建索引的库再跑一次也不报错")
  void scriptIsIdempotent() throws IOException {
    for (String statement :
        resource("/db/migration/sqlite/V27__audit_created_at_index.sql").split(";")) {
      String sql = withoutComments(statement);
      if (!sql.isEmpty()) {
        jdbc.execute(sql);
      }
    }
    assertThat(indexNames("llm_calls")).contains("idx_llm_calls_created_at_id");
    assertThat(indexNames("tool_invocations")).contains("idx_tool_invocations_created_at_id");
  }

  private List<String> explain(String sql) {
    return jdbc.query(
        "EXPLAIN QUERY PLAN " + sql,
        (rs, rowNum) -> rs.getString("detail"),
        "2026-01-01T00:00:00Z",
        "2026-12-31T00:00:00Z");
  }

  private List<String> indexNames(String table) {
    return jdbc.queryForList(
        "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ?",
        String.class,
        table);
  }

  private List<String> indexColumns(String index) {
    return jdbc.query("PRAGMA index_info('" + index + "')", (rs, rowNum) -> rs.getString("name"));
  }

  private static String resource(String path) throws IOException {
    try (InputStream in = AuditCreatedAtIndexSqliteTest.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("missing migration resource " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String withoutComments(String sql) {
    StringBuilder builder = new StringBuilder();
    for (String line : sql.lines().toList()) {
      if (!line.stripLeading().startsWith("--")) {
        builder.append(line).append('\n');
      }
    }
    return builder.toString().strip();
  }
}
