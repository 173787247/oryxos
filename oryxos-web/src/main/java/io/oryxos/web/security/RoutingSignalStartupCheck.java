package io.oryxos.web.security;

import io.oryxos.core.routing.RoutingProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * 启动校验（051 / #477 routing，WARN-only）：routing canary 开了、也配了 residency 白名单和/或 difficulty-models，但
 * **生产代码里没有任何 sensitivity/difficulty 信号生产者**——把"策略已配置但不会生效"这件事显式说出来，不让它静默。
 *
 * <p>为什么必须提示：{@code RoutingContext.open(...)} 在生产源码（{@code src/main/java}）里零调用（只有测试在用），所以 {@code
 * ModelRoutingService.route} 读到的 sensitivity/difficulty 恒为 {@code null} ⇒ {@code
 * applyResidencyFilter} 永远早退、驻留白名单**不过滤任何请求**、{@code ResidencyConstraintException} 不可达、{@code
 * difficulty-models} 永不生效。不提示的话，主动开 canary 的人会以为这些控制已经在守着——静默失效比配置错误更危险。
 *
 * <p>触发条件刻意放宽到「配了 residency 白名单 **或** 配了 difficulty-models」：两项都不依赖别的开关，哪一个单独配上都会静默失效。 两者都配时仍只打一条
 * WARN，文案里只列实际配了的那几项。只 WARN 不阻断：这是「策略还没有信号源」的产品缺口，不是坏配置； {@code routing.enabled=false}（出厂默认）时零噪音。
 *
 * <p>放在 {@code oryxos-web/security} 与 {@code RbacStartupCheck} 同处，{@link
 * ConditionalOnWebApplication} 限定只 SERVLET 模式（serve/gateway）装配，CLI 管理命令不受影响。**覆盖边界：chat
 * 模式不经过本检查**（那里的 routing 同样没有信号， 只是按部署形态取舍暂不提示）。漂移由 {@code RoutingSignalWiringDriftTest}
 * 反向把守：将来如果有人接上生产者，那条测试会变红， 迫使同一个改动回来删掉本 WARN 并把 mock 信号用例换成真实端到端断言。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RoutingSignalStartupCheck implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(RoutingSignalStartupCheck.class);

  /** WARN 正文（测试按其中 marker 过滤）。第一个占位符是实际配置的项，第二个是这些配置失效后的后果——都只列真正配了的， 避免"配了 A 却在报 B"的误导。 */
  static final String MSG_SIGNAL_MISSING =
      "Model routing is enabled (oryxos.routing.enabled=true) with {} configured, but no production"
          + " code path opens RoutingContext: no sensitivity or difficulty signal is ever supplied,"
          + " so {}. Treat this canary as 'policy configured but not enforced' until a signal producer"
          + " (AGENT.md field / profile / request header) is wired.";

  private final RoutingProperties properties;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = {"EI_EXPOSE_REP2"},
      justification =
          "properties 为 Spring 注入的共享单例，构造注入存同一引用正是意图（镜像 RbacStartupCheck 的 SuppressFBWarnings 模式）。")
  public RoutingSignalStartupCheck(RoutingProperties properties) {
    this.properties = properties;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志的两个动态占位符只来自部署配置的 allowlist / difficulty 等级，且每个值在拼接前都过了 s() 去换行；其余文案是常量。")
  @Override
  public void run(ApplicationArguments args) {
    boolean residencyConfigured = !properties.getResidencyProviders().isEmpty();
    boolean difficultyConfigured = !properties.getDifficultyModels().isEmpty();
    if (!properties.isEnabled() || !(residencyConfigured || difficultyConfigured)) {
      LOG.debug(
          "Model routing signal check skipped (enabled={}, residencyProviders={}, difficultyModels={})",
          properties.isEnabled(),
          properties.getResidencyProviders().size(),
          properties.getDifficultyModels().size());
      return;
    }
    LOG.warn(MSG_SIGNAL_MISSING, configuredPolicies(), consequences());
  }

  /** 实际配置的项（只列非空的那几项；两项都配则都列，仍然只有一条 WARN）。 */
  private String configuredPolicies() {
    List<String> configured = new ArrayList<>();
    if (!properties.getResidencyProviders().isEmpty()) {
      configured.add("residency-providers=" + sanitizedAllowlist());
    }
    if (!properties.getDifficultyModels().isEmpty()) {
      configured.add("difficulty-models=" + sanitizedDifficultyLevels());
    }
    return String.join(", ", configured);
  }

  /** 与 {@link #configuredPolicies()} 对齐的失效后果，避免报未配置项。 */
  private String consequences() {
    boolean residencyConfigured = !properties.getResidencyProviders().isEmpty();
    boolean difficultyConfigured = !properties.getDifficultyModels().isEmpty();
    if (residencyConfigured && difficultyConfigured) {
      return "the residency allowlist will not filter any request, ResidencyConstraintException"
          + " is unreachable, and difficulty-models never applies";
    }
    if (residencyConfigured) {
      return "the residency allowlist will not filter any request and ResidencyConstraintException"
          + " is unreachable";
    }
    return "difficulty-models never applies";
  }

  private String sanitizedAllowlist() {
    return properties.getResidencyProviders().stream()
        .map(RoutingSignalStartupCheck::s)
        .collect(Collectors.joining(", ", "[", "]"));
  }

  /** difficulty 等级（map 的 key）按序输出，便于对照配置。 */
  private String sanitizedDifficultyLevels() {
    return properties.getDifficultyModels().keySet().stream()
        .sorted()
        .map(RoutingSignalStartupCheck::s)
        .collect(Collectors.joining(", ", "[", "]"));
  }

  /** 配置值来自部署文件，仍按日志安全口径去掉换行（防日志伪造 / 多行刷屏）。 */
  private static String s(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
