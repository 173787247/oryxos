package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * #1010 的 follow-up：审计窗口查询的 {@code created_at} 索引（PostgreSQL 档，V27 纯 SQL 迁移）。
 *
 * <p>关于执行计划断言的口径：测试表只有几十行，PostgreSQL 本来就会选 Seq Scan，所以 EXPLAIN 前置 {@code SET LOCAL enable_seqscan =
 * off} —— 这证明的是「规划器**能用**这个索引」，而不是「大表上一定 会选它」。真实规模下窗口过滤具有选择性，才会自然走索引。
 */
@PostgresJpaTest
class AuditCreatedAtIndexPostgresTest {

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> PgTestSupport.databaseUrl(AuditCreatedAtIndexPostgresTest.class));
  }

  @Autowired private JdbcTemplate jdbc;

  @Test
  @DisplayName("V27 建出 (created_at, id) 复合索引，两表 indexdef 列序与迁移一致")
  void migrationCreatesCompositeIndexes() {
    assertThat(indexCount("llm_calls", "idx_llm_calls_created_at_id")).isEqualTo(1);
    assertThat(indexCount("tool_invocations", "idx_tool_invocations_created_at_id")).isEqualTo(1);
    assertThat(indexDef("idx_llm_calls_created_at_id")).contains("(created_at, id)");
    assertThat(indexDef("idx_tool_invocations_created_at_id")).contains("(created_at, id)");
  }

  @Test
  @DisplayName("EXPLAIN：禁用 seqscan 后，窗口列表与聚合查询都能用该索引（可用性，非规划器偏好）")
  void plannerCanUseTheIndexForWindowQueries() {
    jdbc.execute("SET LOCAL enable_seqscan = off"); // @DataJpaTest 的用例本身在事务里，LOCAL 生效
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    Instant to = Instant.parse("2026-12-31T00:00:00Z");

    List<String> listPlan =
        jdbc.query(
            "EXPLAIN SELECT id FROM llm_calls WHERE created_at BETWEEN ? AND ?"
                + " ORDER BY created_at DESC, id DESC LIMIT 10",
            (rs, rowNum) -> rs.getString(1),
            Timestamp.from(from),
            Timestamp.from(to));
    assertThat(listPlan).anyMatch(line -> line.contains("idx_llm_calls_created_at_id"));

    List<String> aggregatePlan =
        jdbc.query(
            "EXPLAIN SELECT count(*), sum(duration_ms) FROM llm_calls WHERE created_at BETWEEN ? AND ?",
            (rs, rowNum) -> rs.getString(1),
            Timestamp.from(from),
            Timestamp.from(to));
    assertThat(aggregatePlan).anyMatch(line -> line.contains("idx_llm_calls_created_at_id"));

    List<String> toolPlan =
        jdbc.query(
            "EXPLAIN SELECT id FROM tool_invocations WHERE created_at BETWEEN ? AND ?"
                + " ORDER BY created_at DESC, id DESC LIMIT 10",
            (rs, rowNum) -> rs.getString(1),
            Timestamp.from(from),
            Timestamp.from(to));
    assertThat(toolPlan).anyMatch(line -> line.contains("idx_tool_invocations_created_at_id"));
  }

  @Test
  @DisplayName("V27 脚本可重复执行：IF NOT EXISTS 让已建索引的库再跑一次也不报错")
  void scriptIsIdempotent() throws IOException {
    for (String statement :
        resource("/db/migration/postgresql/V27__audit_created_at_index.sql").split(";")) {
      String sql = withoutComments(statement);
      if (!sql.isEmpty()) {
        jdbc.execute(sql);
      }
    }
    assertThat(indexCount("llm_calls", "idx_llm_calls_created_at_id")).isEqualTo(1);
    assertThat(indexCount("tool_invocations", "idx_tool_invocations_created_at_id")).isEqualTo(1);
  }

  private Integer indexCount(String table, String index) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'"
            + " AND tablename = ? AND indexname = ?",
        Integer.class,
        table,
        index);
  }

  private String indexDef(String index) {
    return jdbc.queryForObject(
        "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
        String.class,
        index);
  }

  private static String resource(String path) throws IOException {
    try (InputStream in = AuditCreatedAtIndexPostgresTest.class.getResourceAsStream(path)) {
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
