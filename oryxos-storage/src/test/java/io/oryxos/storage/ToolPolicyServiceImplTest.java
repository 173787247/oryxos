package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.policy.ToolPolicyService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 020 验收 harness：ToolPolicyServiceImplTest——收敛三步固定序钉死（R5）。守：GLOBAL_DENY 命中/未命中、 EXEMPT 只解除全局
 * deny、AGENT_DENY 最终收紧（三重叠加）、server:* 通配经归属表而非名字前缀、 精确名与通配同层等效、有效集 ⊆ 声明集、空规则=全允（零策略零破坏）。
 */
class ToolPolicyServiceImplTest {

  private ToolPolicyRuleRepository repository;
  private ToolPolicyServiceImpl store;
  private final List<ToolPolicyRule> rules = new ArrayList<>();

  /** mock 工具归属：mcp_a / mcp_b 属于 github-mcp，其余为内置（无归属）。 */
  private final Map<String, String> owners = Map.of("mcp_a", "github-mcp", "mcp_b", "github-mcp");

  @BeforeEach
  void setUp() {
    repository = mock(ToolPolicyRuleRepository.class);
    when(repository.findAll()).thenReturn(rules);
    store = new ToolPolicyServiceImpl(repository, owners::get);
  }

  @Test
  @DisplayName("空规则_全允（零策略零破坏锚点）")
  void emptyRules_allowsEverything() {
    assertThat(store.check("a1", "shell").allowed()).isTrue();
    assertThat(store.filterAllowed("a1", List.of("shell", "mcp_a")))
        .containsExactly("shell", "mcp_a");
  }

  @Test
  @DisplayName("GLOBAL_DENY_所有Agent命中_原因含规则描述")
  void globalDeny_hitsAllAgents() {
    rules.add(rule("GLOBAL_DENY", null, "shell"));

    ToolPolicyService.PolicyDecision decision = store.check("any-agent", "shell");

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).contains("全局禁用").contains("shell");
    assertThat(store.check("any-agent", "read_file").allowed()).isTrue();
  }

  @Test
  @DisplayName("EXEMPT_只解除全局deny_仅对登记Agent生效")
  void exempt_liftsGlobalDenyForRegisteredAgentOnly() {
    rules.add(rule("GLOBAL_DENY", null, "shell"));
    rules.add(rule("AGENT_EXEMPT", "ops-agent", "shell"));

    assertThat(store.check("ops-agent", "shell").allowed()).isTrue();
    assertThat(store.check("other-agent", "shell").allowed()).isFalse();
  }

  @Test
  @DisplayName("三重叠加_AGENT_DENY最终收紧_例外救不回")
  void agentDeny_finalTightening() {
    rules.add(rule("GLOBAL_DENY", null, "shell"));
    rules.add(rule("AGENT_EXEMPT", "ops-agent", "shell"));
    rules.add(rule("AGENT_DENY", "ops-agent", "shell"));

    ToolPolicyService.PolicyDecision decision = store.check("ops-agent", "shell");

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).contains("定向禁用");
  }

  @Test
  @DisplayName("server通配_经归属表命中_而非名字前缀")
  void wildcard_matchesByOwnerNotPrefix() {
    rules.add(rule("GLOBAL_DENY", null, "github-mcp:*"));

    // mcp_a 名字不含前缀，但归属 github-mcp → 命中
    assertThat(store.check("a1", "mcp_a").allowed()).isFalse();
    assertThat(store.check("a1", "mcp_b").allowed()).isFalse();
    // 内置工具无归属 → 不命中
    assertThat(store.check("a1", "shell").allowed()).isTrue();
    // 名字恰好像前缀的工具（无归属）不误伤
    assertThat(store.check("a1", "github-mcp:fake").allowed()).isTrue();
  }

  @Test
  @DisplayName("精确EXEMPT可豁免通配GLOBAL_DENY_同层等效计入命中")
  void exactExempt_liftsWildcardDeny() {
    rules.add(rule("GLOBAL_DENY", null, "github-mcp:*"));
    rules.add(rule("AGENT_EXEMPT", "ops-agent", "mcp_a"));

    assertThat(store.check("ops-agent", "mcp_a").allowed()).isTrue();
    assertThat(store.check("ops-agent", "mcp_b").allowed()).isFalse();
  }

  @Test
  @DisplayName("通配EXEMPT_豁免该server全部工具")
  void wildcardExempt_liftsWholeServer() {
    rules.add(rule("GLOBAL_DENY", null, "github-mcp:*"));
    rules.add(rule("AGENT_EXEMPT", "ops-agent", "github-mcp:*"));

    assertThat(store.check("ops-agent", "mcp_a").allowed()).isTrue();
    assertThat(store.check("ops-agent", "mcp_b").allowed()).isTrue();
    assertThat(store.check("other", "mcp_a").allowed()).isFalse();
  }

  @Test
  @DisplayName("filterAllowed_有效集⊆声明集_保持顺序_EXEMPT不做加法")
  void filterAllowed_subsetOfDeclared() {
    rules.add(rule("GLOBAL_DENY", null, "shell"));
    // EXEMPT 一个未声明的工具：不会把它加进有效集（filterAllowed 只在声明集内过滤）
    rules.add(rule("AGENT_EXEMPT", "a1", "http_post"));

    List<String> effective = store.filterAllowed("a1", List.of("read_file", "shell", "mcp_a"));

    assertThat(effective).containsExactly("read_file", "mcp_a");
  }

  @Test
  @DisplayName("同输入重复求值_结果一致（收敛确定性）")
  void deterministicConvergence() {
    rules.add(rule("GLOBAL_DENY", null, "shell"));
    rules.add(rule("AGENT_EXEMPT", "a1", "shell"));

    for (int i = 0; i < 5; i++) {
      assertThat(store.check("a1", "shell").allowed()).isTrue();
      assertThat(store.check("a2", "shell").allowed()).isFalse();
    }
  }

  private static ToolPolicyRule rule(String type, String agent, String pattern) {
    ToolPolicyRule r = new ToolPolicyRule();
    r.setRuleType(type);
    r.setAgentName(agent);
    r.setPattern(pattern);
    ReflectionTestUtils.setField(r, "id", (long) (type + agent + pattern).hashCode());
    return r;
  }
}
