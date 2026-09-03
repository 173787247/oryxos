package io.oryxos.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V5（原 NotifyChannelSchemaMigration 平移，31 节）：notify_channels 幂等补 config 列。原类是全仓唯一跑在 上下文 refresh 之后的
 * CommandLineRunner（时序泥潭）——收编进 Flyway 后，SecretMigration 读该列时列必已就绪， 其「列未就绪」容错随之拆除（025）。
 */
final class NotifyChannelConfigMigration extends BaseSqliteMigration {

  private static final Logger log = LoggerFactory.getLogger(NotifyChannelConfigMigration.class);

  private static final String CONFIG_COLUMN = "config";

  NotifyChannelConfigMigration() {
    super("5", "notify channel config column");
  }

  @Override
  void migrate(Connection connection) throws SQLException {
    var columns = columns(connection, "notify_channels");
    if (columns.isEmpty() || columns.contains(CONFIG_COLUMN)) {
      return;
    }
    execute(connection, "ALTER TABLE notify_channels ADD COLUMN config TEXT");
    log.info("已迁移 notify_channels：新增 config 列");
  }
}
