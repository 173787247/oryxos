package io.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.core.eval.EvalProperties;
import io.oryxos.core.flow.FlowEngineProperties;
import io.oryxos.core.policy.ApprovalPolicyProperties;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 内置 {@code application.yml} 的「死配置」闸：删掉的键不得回来，活着的块必须照常绑定。
 *
 * <p>两处缺陷都源于「改了没作用」：{@code oryxos.flow} 下的 {@code policy-version} / {@code default-ttl-seconds} /
 * {@code default-approvers} 不是 {@link FlowEngineProperties} 的字段（真正字段在 {@link
 * ApprovalPolicyProperties}，前缀 {@code oryxos.approval}），而 {@code @ConfigurationProperties}
 * 默认忽略未知键；{@code oryxos.eval.*} 则被 {@link EvalProperties} 绑定但没有任何生产消费者（阈值由测试/CI 显式传入）。 两处都已从内置 yml
 * 删除，本用例用 Boot 自己的 yaml loader + binder 钉住当前状态，并证明删除前后绑定值不变。
 */
class DeadConfigBlocksTest {

  private static List<String> propertyNames;
  private static Binder binder;

  @BeforeAll
  static void loadShippedApplicationYaml() throws IOException {
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));
    assertThat(sources).as("内置 application.yml 必须能加载").isNotEmpty();
    PropertySource<?> yaml = sources.get(0);
    assertThat(yaml).as("本用例靠枚举键名断言「没有死键」，来源必须是可枚举的").isInstanceOf(EnumerablePropertySource.class);
    propertyNames = List.of(((EnumerablePropertySource<?>) yaml).getPropertyNames());
    assertThat(propertyNames)
        .as("扫描本身要有效：一个键都读不到的用例永远不会红")
        .contains("oryxos.flow.engine-enabled", "oryxos.approval.enabled");
    binder = new Binder(ConfigurationPropertySources.from(yaml));
  }

  @Test
  @DisplayName("A：oryxos.flow 下不再有 ApprovalPolicyProperties 的三个字段（它们曾被静默忽略）")
  void flowBlockCarriesOnlyItsOwnKeys() {
    assertThat(propertyNames)
        .as("这三行不是 FlowEngineProperties 的字段，写在这里不生效")
        .doesNotContain(
            "oryxos.flow.policy-version",
            "oryxos.flow.default-ttl-seconds",
            "oryxos.flow.default-approvers");

    FlowEngineProperties flow =
        binder
            .bind("oryxos.flow", Bindable.of(FlowEngineProperties.class))
            .orElseThrow(() -> new AssertionError("oryxos.flow 绑定失败"));
    assertThat(flow.isEngineEnabled()).isFalse();
    assertThat(flow.getDefaultMaxRetries()).isZero();
    assertThat(flow.isCompensationEnabled()).isFalse();
  }

  @Test
  @DisplayName("B：内置 application.yml 不再声明 oryxos.eval.*（无生产消费者，绑定值仍是代码默认）")
  void shippedYamlDoesNotClaimEvalProperties() {
    assertThat(propertyNames).noneMatch(name -> name.startsWith("oryxos.eval"));

    EvalProperties eval =
        binder
            .bind("oryxos.eval", Bindable.of(EvalProperties.class))
            .orElseGet(EvalProperties::new);
    assertThat(eval.isGateEnabled()).isFalse();
    assertThat(eval.getMinSuccessRate()).isEqualTo(0.80);
    assertThat(eval.getMinToolAccuracy()).isEqualTo(0.80);
    assertThat(eval.getMinCitationQuality()).isEqualTo(0.70);
    assertThat(eval.getMaxLatencyMsAvg()).isEqualTo(10_000.0);
    assertThat(eval.getMaxCostMicrosTotal()).isEqualTo(5_000_000L);
    assertThat(eval.getMaxRateRegression()).isEqualTo(0.05);
    assertThat(eval.getMaxLatencyRegressionMs()).isEqualTo(2_000.0);
  }

  @Test
  @DisplayName("零行为变化：删掉的三行本来就等于 oryxos.approval 的代码默认值")
  void approvalDefaultsMatchTheRemovedFlowValues() {
    ApprovalPolicyProperties approval =
        binder
            .bind("oryxos.approval", Bindable.of(ApprovalPolicyProperties.class))
            .orElseThrow(() -> new AssertionError("oryxos.approval 绑定失败"));

    assertThat(propertyNames)
        .as("删掉的三行没有被挪到 approval 块里——本 PR 不改 approval 的配置面")
        .doesNotContain(
            "oryxos.approval.policy-version",
            "oryxos.approval.default-ttl-seconds",
            "oryxos.approval.default-approvers");
    assertThat(approval.getPolicyVersion()).isEqualTo("1");
    assertThat(approval.getDefaultTtlSeconds()).isEqualTo(3600);
    assertThat(approval.getDefaultApprovers()).isEmpty();
  }
}
