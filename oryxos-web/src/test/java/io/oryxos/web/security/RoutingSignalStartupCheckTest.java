package io.oryxos.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.oryxos.core.routing.ProviderModelRef;
import io.oryxos.core.routing.RoutingProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 051 / #477 routing：canary 开且配了路由策略、但没有信号生产者时必须显式 WARN（WARN-only，不阻断启动）。 */
class RoutingSignalStartupCheckTest {

  /** 文案里"没有生产者"那半句：断言用它，避免整句硬编码。 */
  private static final String NO_PRODUCER_MARKER = "no production code path opens RoutingContext";

  private final RoutingProperties properties = new RoutingProperties();

  private final RoutingSignalStartupCheck check = new RoutingSignalStartupCheck(properties);

  @Test
  @DisplayName("只配residency白名单：一条WARN，只列residency、只报residency失效")
  void residencyOnlyWarnsAboutResidency() {
    properties.setEnabled(true);
    properties.setResidencyProviders(List.of("deepseek", "local-llm"));

    List<ILoggingEvent> warnings = runCapturingWarnings();

    assertEquals(1, warnings.size(), "只该一条 WARN，不能刷屏");
    String message = warnings.get(0).getFormattedMessage();
    assertTrue(message.contains(NO_PRODUCER_MARKER), "要点名没有任何生产者提供信号: " + message);
    assertTrue(message.contains("residency-providers"), "要列出实际配置的项: " + message);
    assertTrue(
        message.contains("deepseek") && message.contains("local-llm"), "带上配置的白名单: " + message);
    assertTrue(message.contains("ResidencyConstraintException"), "要让运维看到驻留约束不可达: " + message);
    assertFalse(message.contains("difficulty-models="), "没配的项不能出现在配置清单里: " + message);
  }

  @Test
  @DisplayName("只配difficulty-models：同样一条WARN，只列difficulty、报difficulty失效")
  void difficultyOnlyWarnsAboutDifficulty() {
    properties.setEnabled(true);
    properties.setDifficultyModels(Map.of("high", ref("deepseek", "reasoner")));

    List<ILoggingEvent> warnings = runCapturingWarnings();

    assertEquals(1, warnings.size(), "difficulty-models 单独配置也永不生效，必须提示");
    String message = warnings.get(0).getFormattedMessage();
    assertTrue(message.contains(NO_PRODUCER_MARKER), "要点名没有任何生产者提供信号: " + message);
    assertTrue(message.contains("difficulty-models"), "要列出实际配置的项: " + message);
    assertTrue(message.contains("high"), "带上配置的 difficulty 等级: " + message);
    assertTrue(message.contains("difficulty-models never applies"), "要报 difficulty 失效: " + message);
    assertFalse(message.contains("residency-providers="), "没配的项不能出现在配置清单里: " + message);
  }

  @Test
  @DisplayName("residency与difficulty都配：仍只一条WARN，两项都列")
  void bothConfiguredStillSingleWarn() {
    properties.setEnabled(true);
    properties.setResidencyProviders(List.of("deepseek"));
    properties.setDifficultyModels(Map.of("high", ref("deepseek", "reasoner")));

    List<ILoggingEvent> warnings = runCapturingWarnings();

    assertEquals(1, warnings.size(), "两项都配也只能一条 WARN");
    String message = warnings.get(0).getFormattedMessage();
    assertTrue(message.contains("residency-providers="), "配置清单要含 residency: " + message);
    assertTrue(message.contains("difficulty-models="), "配置清单要含 difficulty: " + message);
    assertTrue(message.contains("ResidencyConstraintException"), message);
    assertTrue(message.contains("difficulty-models never applies"), message);
  }

  @Test
  @DisplayName("routing.enabled=false（出厂默认）：零 WARN")
  void disabledDoesNotWarn() {
    properties.setEnabled(false);
    properties.setResidencyProviders(List.of("deepseek"));
    properties.setDifficultyModels(Map.of("high", ref("deepseek", "reasoner")));

    assertEquals(List.of(), runCapturingWarnings(), "默认档不能制造噪音");
  }

  @Test
  @DisplayName("routing开但两项都没配：零 WARN（没有会静默失效的路由策略）")
  void enabledWithoutAnyPolicyDoesNotWarn() {
    properties.setEnabled(true);

    assertEquals(List.of(), runCapturingWarnings(), "没有配任何路由策略时这一条不成立，不能报警");
  }

  private static ProviderModelRef ref(String provider, String model) {
    ProviderModelRef ref = new ProviderModelRef();
    ref.setProvider(provider);
    ref.setModel(model);
    return ref;
  }

  /** 只收 {@link RoutingSignalStartupCheck} 这个命名 logger 的 WARN，避免把别处的日志算进来。 */
  private List<ILoggingEvent> runCapturingWarnings() {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(RoutingSignalStartupCheck.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      check.run(null);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
    return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
  }
}
