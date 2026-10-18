package io.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.oryxos.web.config.WebRbacProperties;
import io.oryxos.web.security.RbacEndpointCoverageCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * {@code RbacEndpointCoverageCheck} 的拒启相位：RBAC 开启且存在未登记的受保护端点时，必须在 Web Server 打开端口之前失败。
 *
 * <p>用一个只有「一个未登记控制器」的最小 Servlet 上下文制造 gap（真实应用的端点都是登记过的，造不出该场景）。端口时序由共享的 {@link
 * WebServerStartProbe} 断言。
 */
class RbacEndpointCoverageRefusalBeforePortTest {

  @Test
  @DisplayName("RBAC 开启且有未登记受保护端点_在端口打开之前拒启")
  void refusalHappensBeforeThePortOpens() {
    WebServerStartProbe.reset();

    Throwable failure =
        catchThrowable(
            () ->
                new SpringApplicationBuilder(CoverageApp.class, WebServerStartProbe.class)
                    .web(WebApplicationType.SERVLET)
                    .run(
                        "--server.port=0",
                        "--spring.config.location=classpath:/minimal-startup-app.yml",
                        "--springdoc.api-docs.enabled=false",
                        "--springdoc.swagger-ui.enabled=false"));

    assertThat(failure).as("存在未登记受保护端点时 RBAC 启动校验必须拒启").isNotNull();
    assertThat(WebServerStartProbe.messagesOf(failure))
        .as("失败原因要是那条未登记端点的指路信息")
        .anyMatch(message -> message.contains("not registered in RequestActionResolver"));
    assertThat(WebServerStartProbe.started())
        .as("拒启必须发生在 Web Server 打开端口之前（ApplicationRunner 会在此之后才跑）")
        .isFalse();
  }

  /** 最小应用：开 RBAC、装检查与一个未登记的受保护端点。 */
  @Configuration
  @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
  static class CoverageApp {

    @Bean
    WebRbacProperties webRbacProperties() {
      WebRbacProperties properties = new WebRbacProperties();
      properties.setEnabled(true);
      return properties;
    }

    @Bean
    RbacEndpointCoverageCheck rbacEndpointCoverageCheck(
        WebRbacProperties rbacProperties,
        @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping) {
      return new RbacEndpointCoverageCheck(rbacProperties, handlerMapping);
    }

    @Bean
    RogueController rogueController() {
      return new RogueController();
    }
  }

  /** 受保护前缀下、没有在 {@code RequestActionResolver} 登记的端点：coverage 检查必须把它当 gap。 */
  @RestController
  static class RogueController {

    @GetMapping("/api/v1/zzz-rogue-probe")
    String probe() {
      return "rogue";
    }
  }
}
