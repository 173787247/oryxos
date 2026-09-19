package io.oryxos.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfigApprovalPolicyServiceImplTest {

  @Test
  @DisplayName("default off -> always ALLOW")
  void disabled_alwaysAllow() {
    var cfg =
        new ApprovalPolicyConfig(
            false,
            "v-test",
            3600,
            List.of(),
            List.of(
                new ApprovalPolicyConfig.Rule(
                    "shell",
                    List.of(),
                    List.of("shell"),
                    List.of(),
                    List.of("admin"),
                    60,
                    ApprovalOutcome.REQUIRE_APPROVAL,
                    ApprovalDenySemantics.TIMEOUT_REJECT,
                    ApprovalDenySemantics.REJECT)));
    var svc = new ConfigApprovalPolicyServiceImpl(cfg);
    assertThat(svc.evaluate("a1", "shell", "{}").allowed()).isTrue();
    assertThat(svc.evaluate("a1", "shell", "{}").policyVersion()).isEqualTo("v-test");
  }

  @Test
  @DisplayName("action type SHELL -> REQUIRE_APPROVAL")
  void actionType_shell_requiresApproval() {
    var cfg =
        new ApprovalPolicyConfig(
            true,
            "2",
            1800,
            List.of("ops"),
            List.of(
                new ApprovalPolicyConfig.Rule(
                    "shell-global",
                    List.of("*"),
                    List.of(),
                    List.of(HighRiskActionType.SHELL),
                    List.of(),
                    null,
                    ApprovalOutcome.REQUIRE_APPROVAL,
                    ApprovalDenySemantics.TIMEOUT_REJECT,
                    ApprovalDenySemantics.REJECT)));
    var svc = new ConfigApprovalPolicyServiceImpl(cfg);
    var d = svc.evaluate("ops-agent", "shell", null);
    assertThat(d.requiresApproval()).isTrue();
    assertThat(d.ruleId()).isEqualTo("shell-global");
    assertThat(d.approvers()).containsExactly("ops");
    assertThat(d.ttlSeconds()).isEqualTo(1800);
    assertThat(d.actionType()).isEqualTo(HighRiskActionType.SHELL);
  }

  @Test
  @DisplayName("agent+tool DENY -> not visible")
  void agentTool_deny() {
    var cfg =
        new ApprovalPolicyConfig(
            true,
            "1",
            3600,
            List.of(),
            List.of(
                new ApprovalPolicyConfig.Rule(
                    "deny-notify",
                    List.of("public-bot"),
                    List.of("notify"),
                    List.of(),
                    List.of(),
                    120,
                    ApprovalOutcome.DENY,
                    ApprovalDenySemantics.TIMEOUT_REJECT,
                    ApprovalDenySemantics.REJECT)));
    var svc = new ConfigApprovalPolicyServiceImpl(cfg);
    var d = svc.evaluate("public-bot", "notify", "{}");
    assertThat(d.denied()).isTrue();
    assertThat(d.visibleInPrompt()).isFalse();
    assertThat(svc.evaluate("ops-agent", "notify", "{}").allowed()).isTrue();
  }

  @Test
  @DisplayName("MCP server:* wildcard match")
  void mcpWildcard() {
    var cfg =
        new ApprovalPolicyConfig(
            true,
            "1",
            3600,
            List.of("sec"),
            List.of(
                new ApprovalPolicyConfig.Rule(
                    "gh-mcp",
                    List.of(),
                    List.of("github-mcp:*"),
                    List.of(),
                    List.of(),
                    null,
                    ApprovalOutcome.REQUIRE_APPROVAL,
                    ApprovalDenySemantics.TIMEOUT_REJECT,
                    ApprovalDenySemantics.REJECT)));
    var svc =
        new ConfigApprovalPolicyServiceImpl(
            cfg,
            new HighRiskActionClassifier(name -> "github-mcp"),
            name -> "github-mcp",
            ApprovalAuditRecorder.NOOP);
    assertThat(svc.evaluate("a1", "create_issue", null).requiresApproval()).isTrue();
  }

  @Test
  @DisplayName("HIT_REQUIRE audit + APPROVED stub")
  void hitAndApproveStub_audited() {
    List<ApprovalAuditRecorder.ApprovalAuditEvent> events = new CopyOnWriteArrayList<>();
    ApprovalAuditRecorder recorder = events::add;
    var cfg =
        new ApprovalPolicyConfig(
            true,
            "9",
            60,
            List.of("admin"),
            List.of(
                new ApprovalPolicyConfig.Rule(
                    "r1",
                    List.of(),
                    List.of("shell"),
                    List.of(),
                    List.of("admin"),
                    30,
                    ApprovalOutcome.REQUIRE_APPROVAL,
                    ApprovalDenySemantics.TIMEOUT_REJECT,
                    ApprovalDenySemantics.REJECT)));
    var svc =
        new ConfigApprovalPolicyServiceImpl(
            cfg, new HighRiskActionClassifier(), name -> null, recorder);
    var d = svc.evaluate("a1", "shell", null);
    svc.recordHit("s1", "a1", "shell", d);
    assertThat(
            svc.recordHumanDecision(
                new ApprovalHumanDecision("s1", "a1", "shell", "9", "r1", true, "admin", "ok")))
        .isTrue();
    assertThat(events).hasSize(2);
    assertThat(events.get(0).kind()).isEqualTo(ApprovalAuditKind.HIT_REQUIRE);
    assertThat(events.get(1).kind()).isEqualTo(ApprovalAuditKind.APPROVED);
    assertThat(events.get(1).actor()).isEqualTo("admin");
  }

  @Test
  @DisplayName("Properties toConfig loads rules")
  void properties_toConfig() {
    var props = new ApprovalPolicyProperties();
    props.setEnabled(true);
    props.setPolicyVersion("p3");
    props.setDefaultTtlSeconds(99);
    props.setDefaultApprovers(new ArrayList<>(List.of("a")));
    var rule = new ApprovalPolicyProperties.RuleProperties();
    rule.setId("file-mut");
    rule.setActionTypes(List.of("FILE_MUTATION"));
    rule.setEffect("require_approval");
    props.setRules(List.of(rule));
    var cfg = props.toConfig();
    assertThat(cfg.enabled()).isTrue();
    assertThat(cfg.policyVersion()).isEqualTo("p3");
    assertThat(cfg.rules()).hasSize(1);
    assertThat(cfg.rules().get(0).actionTypes()).containsExactly(HighRiskActionType.FILE_MUTATION);
    var svc = new ConfigApprovalPolicyServiceImpl(cfg);
    assertThat(svc.evaluate("a", "write_file", null).requiresApproval()).isTrue();
    assertThat(svc.evaluate("a", "read_file", null).allowed()).isTrue();
  }
}
