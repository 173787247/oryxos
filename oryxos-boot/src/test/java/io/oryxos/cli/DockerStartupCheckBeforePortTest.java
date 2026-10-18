package io.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.oryxos.boot.WebServerStartProbe;
import io.oryxos.tool.sandbox.ExecutionBackendProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code DockerBackendStartupCheck} 的拒启相位：docker 后端不可用时，必须在 Web Server 打开端口之前失败（否则副本会先被探针判成
 * 就绪，再带病运行）。
 *
 * <p>用最小 Servlet 上下文 + 注入桩探针（返回 "docker: not found"）而不是整机启动：这样不依赖宿主机是否装了 docker，也避免真跑 {@code docker
 * info}/{@code pull} 的时延。桩探针来自该类的包级构造（同 package 用例可达）。端口时序由共享的 {@link WebServerStartProbe} 断言。
 */
class DockerStartupCheckBeforePortTest {

  @Test
  @DisplayName("docker 后端不可用_在端口打开之前拒启")
  void refusalHappensBeforeThePortOpens() {
    WebServerStartProbe.reset();

    Throwable failure =
        catchThrowable(
            () ->
                new SpringApplicationBuilder(DockerApp.class, WebServerStartProbe.class)
                    .web(WebApplicationType.SERVLET)
                    .run(
                        "--server.port=0",
                        "--spring.config.location=classpath:/minimal-startup-app.yml",
                        "--springdoc.api-docs.enabled=false",
                        "--springdoc.swagger-ui.enabled=false"));

    assertThat(failure).as("docker 后端声明了却不可用时必须拒启").isNotNull();
    assertThat(WebServerStartProbe.messagesOf(failure))
        .as("失败原因要指明 docker CLI 不可用")
        .anyMatch(message -> message.contains("docker CLI 不可用"));
    assertThat(WebServerStartProbe.started())
        .as("拒启必须发生在 Web Server 打开端口之前（ApplicationRunner 会在此之后才跑）")
        .isFalse();
  }

  /** 最小应用：只装被测检查与它的配置/桩探针。 */
  @Configuration
  @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
  static class DockerApp {

    @Bean
    DockerBackendStartupCheck dockerBackendStartupCheck() {
      ExecutionBackendProperties props =
          new ExecutionBackendProperties("docker", "alpine:3.20", null, null, null, null);
      return new DockerBackendStartupCheck(props, argv -> "docker: not found");
    }
  }
}
