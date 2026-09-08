package io.oryxos.storage.migration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * SQLite 存量收敛迁移 V2~V5 的装配（025）：仅 datasource url 为 SQLite 时注册——这些迁移是 SQLite 存量库 的收敛器（幂等依据 PRAGMA，PG
 * 上无意义也无存量：PG 目录的 V1 基线即完整最终结构）。Spring Boot 的 Flyway 自动配置收集容器内全部 {@code JavaMigration} bean 并入迁移序列。
 */
@Configuration(proxyBeanMethods = false)
@Conditional(SqliteMigrationsConfiguration.OnSqliteDatasource.class)
public class SqliteMigrationsConfiguration {

  /** url 前缀判定（@ConditionalOnProperty 无前缀匹配能力）；缺省 url 即内置 SQLite 默认档。 */
  static class OnSqliteDatasource implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      String url =
          context.getEnvironment().getProperty("spring.datasource.url", "jdbc:sqlite:oryxos.db");
      return url.startsWith("jdbc:sqlite:");
    }
  }

  @Bean
  AuditColumnsMigration auditColumnsMigration() {
    return new AuditColumnsMigration();
  }

  @Bean
  MemoryAgentColumnMigration memoryAgentColumnMigration() {
    return new MemoryAgentColumnMigration();
  }

  @Bean
  ScheduleIdentityMigration scheduleIdentityMigration() {
    return new ScheduleIdentityMigration();
  }

  @Bean
  NotifyChannelConfigMigration notifyChannelConfigMigration() {
    return new NotifyChannelConfigMigration();
  }
}
