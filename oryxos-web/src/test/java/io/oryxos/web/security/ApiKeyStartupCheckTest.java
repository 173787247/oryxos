package io.oryxos.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.oryxos.storage.ApiKeyService;
import io.oryxos.web.config.WebApiKeyProperties;
import io.oryxos.web.config.WebAuthProperties;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 018 验收 harness：ApiKeyStartupCheckTest——两条启动 WARN 钉死且均不抛异常（FR-012，与 012 fail-fast 有意不同）。 用 Logback
 * ListAppender 捕获 WARN。
 */
class ApiKeyStartupCheckTest {

  private WebApiKeyProperties apiKeyProperties;
  private WebAuthProperties authProperties;
  private ApiKeyService apiKeyService;
  private ApiKeyStartupCheck check;
  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void setUp() {
    apiKeyProperties = new WebApiKeyProperties();
    authProperties = new WebAuthProperties();
    apiKeyService = mock(ApiKeyService.class);
    check = new ApiKeyStartupCheck(apiKeyProperties, authProperties, apiKeyService);
    logger = (Logger) LoggerFactory.getLogger(ApiKeyStartupCheck.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
  }

  @Test
  @DisplayName("enabled=true且无有效Key_WARN提示apikey_add_不抛异常")
  void enabledNoKey_warnsWithoutThrowing() {
    apiKeyProperties.setEnabled(true);
    authProperties.setEnabled(true);
    when(apiKeyService.hasActiveKey()).thenReturn(false);

    assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
    assertThat(warnMessages()).anyMatch(m -> m.contains("oryxos apikey add"));
  }

  @Test
  @DisplayName("enabled=true且auth关闭_WARN管理台不可用_不抛异常")
  void enabledAuthOff_warnsWithoutThrowing() {
    apiKeyProperties.setEnabled(true);
    authProperties.setEnabled(false);
    when(apiKeyService.hasActiveKey()).thenReturn(true);

    assertThatCode(() -> check.run(null)).doesNotThrowAnyException();
    assertThat(warnMessages()).anyMatch(m -> m.contains("Admin console"));
  }

  @Test
  @DisplayName("正常配置（双开且有Key）_无WARN")
  void healthyConfig_noWarn() {
    apiKeyProperties.setEnabled(true);
    authProperties.setEnabled(true);
    when(apiKeyService.hasActiveKey()).thenReturn(true);

    check.run(null);

    assertThat(warnMessages()).isEmpty();
  }

  @Test
  @DisplayName("enabled=false_跳过校验_无WARN")
  void disabled_skipsCheck() {
    apiKeyProperties.setEnabled(false);
    authProperties.setEnabled(false);

    check.run(null);

    assertThat(warnMessages()).isEmpty();
  }

  private List<String> warnMessages() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }
}
