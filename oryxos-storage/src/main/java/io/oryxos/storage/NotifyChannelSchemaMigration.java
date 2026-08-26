package io.oryxos.storage;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SQLite 表结构增量迁移：给已存在的 {@code notify_channels} 补 {@code config} 列。
 *
 * <p>背景：表结构唯一权威是手工 {@code schema.sql}（{@code ddl-auto: none}），而 {@code CREATE TABLE IF NOT EXISTS}
 * 不会给旧库加新列、SQLite 又无 {@code ADD COLUMN IF NOT EXISTS}，故用一个幂等的 {@link CommandLineRunner} 在启动时按
 * {@code PRAGMA table_info} 探测缺列再 {@code ALTER TABLE ... ADD COLUMN}。新库由 schema.sql 直接建含该列，此处探测后空转。
 */
@Component
public class NotifyChannelSchemaMigration implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(NotifyChannelSchemaMigration.class);

  private static final String TABLE = "notify_channels";
  private static final String COLUMN = "config";

  private final JdbcTemplate jdbc;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "注入的 JdbcTemplate 是 Spring 共享 Bean（连接工厂），本就不应防御性拷贝。")
  public NotifyChannelSchemaMigration(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void run(String... args) {
    if (!hasColumn()) {
      jdbc.execute("ALTER TABLE " + TABLE + " ADD COLUMN " + COLUMN + " TEXT");
      log.info("已迁移 {}：新增 {} 列", TABLE, COLUMN);
    }
  }

  private boolean hasColumn() {
    List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(" + TABLE + ")");
    for (Map<String, Object> row : columns) {
      if (COLUMN.equals(row.get("name"))) {
        return true;
      }
    }
    return false;
  }
}
