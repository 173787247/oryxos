package io.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

import io.oryxos.storage.WebUserService;
import io.oryxos.web.config.WebAuthProperties;
import io.oryxos.web.security.AuthStartupCheck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code AuthStartupCheck} 的拒启相位：{@code auth.enabled=true} 且库里没有 enabled 账号时，必须在 Web Server 打开端口之前
 * 就失败（这是 {@code AuthStartupCheck} 的生产语义；原先 {@code ApplicationRunner} 会先让端口可接请求再拒启）。
 *
 * <p>用最小 Servlet 上下文（只装这个检查）而不是整台 OryxOS：拒启条件只依赖一个配置对象与 {@code WebUserService}，这样测试快且
 * 不碰真实账号库。端口时序由共享的 {@link WebServerStartProbe} 断言。
 */
class AuthStartupRefusalBeforePortTest {

  @Test
  @DisplayName("auth 开启但无 enabled 账号_在端口打开之前拒启")
  void refusalHappensBeforeThePortOpens() {
    WebServerStartProbe.reset();

    Throwable failure =
        catchThrowable(
            () ->
                new SpringApplicationBuilder(AuthApp.class, WebServerStartProbe.class)
                    .web(WebApplicationType.SERVLET)
                    .run(
                        "--server.port=0",
                        "--spring.config.location=classpath:/minimal-startup-app.yml",
                        "--springdoc.api-docs.enabled=false",
                        "--springdoc.swagger-ui.enabled=false"));

    assertThat(failure).as("无账号时 web auth 启动校验必须拒启").isNotNull();
    assertThat(WebServerStartProbe.messagesOf(failure))
        .as("失败原因要是那条可执行的指路信息，而不是别的启动问题")
        .anyMatch(message -> message.contains("no enabled account found"));
    assertThat(WebServerStartProbe.started())
        .as("拒启必须发生在 Web Server 打开端口之前（ApplicationRunner 会在此之后才跑）")
        .isFalse();
  }

  /** 最小应用：只装被校验的依赖与检查本身。 */
  @Configuration
  @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
  static class AuthApp {

    @Bean
    WebAuthProperties webAuthProperties() {
      WebAuthProperties properties = new WebAuthProperties();
      properties.setEnabled(true);
      return properties;
    }

    @Bean
    WebUserService webUserService() {
      // hasEnabledAccount() 缺省 false：正是要拒启的配置
      return mock(WebUserService.class);
    }

    @Bean
    AuthStartupCheck authStartupCheck(WebAuthProperties properties, WebUserService users) {
      return new AuthStartupCheck(properties, users);
    }
  }
}
