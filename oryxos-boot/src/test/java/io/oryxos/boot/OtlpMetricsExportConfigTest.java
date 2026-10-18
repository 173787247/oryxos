package io.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * #471：指标 OTLP 导出开关语义——默认禁用（零连接零导出，Prometheus 拉取口径不变）； 显式启用 + url 后装配 OTLP registry 推送。
 *
 * <p>{@link ConfigDataApplicationContextInitializer} 让切片加载本仓内置的 {@code application.yml}（与 {@code
 * SandboxDefaultsConfigTest}/{@code SqliteDataSourceConfigTest} 同款）；默认用例不传任何 enabled 属性，断言的才是
 * 「仓库默认」，而不是 Spring Boot 自动配置本身的行为。
 */
class OtlpMetricsExportConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(
              new ConfigDataApplicationContextInitializer()) // 内置 application.yml 是唯一默认值来源
          .withConfiguration(
              AutoConfigurations.of(
                  MetricsAutoConfiguration.class, OtlpMetricsExportAutoConfiguration.class));

  @Test
  @DisplayName("默认（application.yml enabled=false 口径）：不装配 OTLP registry")
  void disabledByDefaultConfig() {
    // 不显式传 enabled：默认值只能来自内置 application.yml（改 yml 必须让本用例红）
    runner.run(context -> assertThat(context).doesNotHaveBean(OtlpMeterRegistry.class));
  }

  @Test
  @DisplayName("启用 + url：装配 OTLP registry（推送型导出通道就绪）")
  void enabledWithUrlCreatesRegistry() {
    runner
        .withPropertyValues(
            "management.otlp.metrics.export.enabled=true",
            "management.otlp.metrics.export.url=http://collector:4318/v1/metrics")
        .run(context -> assertThat(context).hasSingleBean(OtlpMeterRegistry.class));
  }
}
