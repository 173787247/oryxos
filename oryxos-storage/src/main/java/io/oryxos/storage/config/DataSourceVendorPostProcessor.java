package io.oryxos.storage.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 按 datasource url 自动适配 vendor（025，FR-006）：内置默认配置是 SQLite 档（驱动、SQLiteDialect、 WAL/busy_timeout 两个
 * PRAGMA 型 Hikari 属性）；用户把 url 换成 {@code jdbc:postgresql:} 时，这三类 SQLite 专属项必须让位——PRAGMA 键传给 PG
 * 驱动是未定义行为、方言错配直接启动失败，而要求用户手工 「反配」它们是易错面。此处以最高优先级属性源把它们清空/覆盖：driver 与方言交还 Spring Boot 按 url
 * 自动推导，用户只需配 url + 账密。SQLite/缺省 url 零动作（默认档行为不变）。
 */
public class DataSourceVendorPostProcessor implements EnvironmentPostProcessor {

  private static final String URL_KEY = "spring.datasource.url";
  private static final String POSTGRES_PREFIX = "jdbc:postgresql:";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    String url = environment.getProperty(URL_KEY, "");
    if (!url.startsWith(POSTGRES_PREFIX)) {
      return;
    }
    Map<String, Object> overrides = new HashMap<>(8);
    // 内置 yml 的 SQLite 专属默认在 PG 下全部让位；空值即「未配置」，Boot 按 url 推导驱动与方言
    overrides.put("spring.datasource.driver-class-name", "");
    overrides.put("spring.jpa.database-platform", "");
    overrides.put("spring.datasource.hikari.data-source-properties.journal_mode", "");
    overrides.put("spring.datasource.hikari.data-source-properties.busy_timeout", "");
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("oryxosPostgresVendorOverrides", overrides));
  }
}
