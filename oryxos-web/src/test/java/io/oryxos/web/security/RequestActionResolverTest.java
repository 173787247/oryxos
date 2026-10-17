package io.oryxos.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.core.policy.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RequestActionResolver 契约 §4.2 + 盘点补登记（v2 schedules / Alipay）。 */
class RequestActionResolverTest {

  @Test
  @DisplayName("豁免：OPTIONS / health / auth / actuator-health / inbound / Alipay POST /api/v1")
  void skipPaths() {
    assertSkip("OPTIONS", "/api/v1/anything");
    assertSkip("GET", "/api/v1/health");
    assertSkip("POST", "/api/v1/auth/login");
    assertSkip("GET", "/actuator/health");
    assertSkip("GET", "/actuator/health/liveness");
    assertSkip("POST", "/api/v1/channels/inbound/feishu");
    assertSkip("POST", "/api/v1/approvals/callbacks/feishu");
    assertSkip("POST", "/api/v1/approvals/callbacks/wecom");
    assertSkip("POST", "/api/v1");
    assertSkip("POST", "/api/v1/");
  }

  @Test
  @DisplayName("agents invoke → RUN_AGENT；GET agents → READ；POST agents → MANAGE_AGENTS")
  void agents() {
    assertAction("POST", "/api/v1/agents/demo/invoke", Action.RUN_AGENT);
    assertAction("GET", "/api/v1/agents", Action.READ_WORKSPACE);
    assertAction("POST", "/api/v1/agents", Action.MANAGE_AGENTS);
  }

  @Test
  @DisplayName("tool-policy / sandbox → MANAGE_POLICIES；profiles GET → READ")
  void policiesAndProfiles() {
    assertAction("GET", "/api/v1/tool-policy/rules", Action.MANAGE_POLICIES);
    assertAction("PUT", "/api/v1/sandbox/whitelist", Action.MANAGE_POLICIES);
    assertAction("GET", "/api/v1/approvals", Action.MANAGE_POLICIES);
    assertAction("POST", "/api/v1/approvals/cp-1/decide", Action.MANAGE_POLICIES);
    assertAction("GET", "/api/v1/profiles", Action.READ_WORKSPACE);
  }

  @Test
  @DisplayName("v2 schedules 与 v1 同档 MANAGE_AGENTS（写）/ READ（读）")
  void v2Schedules() {
    assertAction("GET", "/api/v2/schedules", Action.READ_WORKSPACE);
    assertAction("POST", "/api/v2/schedules", Action.MANAGE_AGENTS);
    assertAction("POST", "/api/v2/agents/demo/schedules/nightly/run", Action.MANAGE_AGENTS);
  }

  @Test
  @DisplayName("teams / orgs / user-teams / identity-mappings → MANAGE_MEMBERS（含 GET，ADMIN 门）")
  void teamsApi() {
    assertAction("GET", "/api/v1/teams", Action.MANAGE_MEMBERS);
    assertAction("POST", "/api/v1/teams", Action.MANAGE_MEMBERS);
    assertAction("PATCH", "/api/v1/teams/eng", Action.MANAGE_MEMBERS);
    assertAction("PUT", "/api/v1/teams/eng/org", Action.MANAGE_MEMBERS);
    assertAction("DELETE", "/api/v1/teams/eng", Action.MANAGE_MEMBERS);
    assertAction("GET", "/api/v1/orgs", Action.MANAGE_MEMBERS);
    assertAction("POST", "/api/v1/orgs", Action.MANAGE_MEMBERS);
    assertAction("PATCH", "/api/v1/orgs/acme", Action.MANAGE_MEMBERS);
    assertAction("DELETE", "/api/v1/orgs/acme", Action.MANAGE_MEMBERS);
    assertAction("PUT", "/api/v1/orgs/acme/parent", Action.MANAGE_MEMBERS);
    assertAction("GET", "/api/v1/users/alice/teams", Action.MANAGE_MEMBERS);
    assertAction("PUT", "/api/v1/users/alice/teams/eng", Action.MANAGE_MEMBERS);
    assertAction("DELETE", "/api/v1/users/alice/teams/eng", Action.MANAGE_MEMBERS);
    assertAction("GET", "/api/v1/identity-mappings", Action.MANAGE_MEMBERS);
    assertAction("POST", "/api/v1/identity-mappings", Action.MANAGE_MEMBERS);
    assertAction("DELETE", "/api/v1/identity-mappings", Action.MANAGE_MEMBERS);
  }

  @Test
  @DisplayName("audit 前缀只读：GET/HEAD → READ_AUDIT；非读方法未登记（fail-closed）")
  void auditPrefixFailsClosedForWrites() {
    assertAction("GET", "/api/v1/audit/export", Action.READ_AUDIT);
    assertAction("GET", "/api/v1/audit/llm/summary", Action.READ_AUDIT);
    assertAction("HEAD", "/api/v1/audit/llm", Action.READ_AUDIT);
    assertThat(RequestActionResolver.resolve("POST", "/api/v1/audit/anything")).isNull();
    assertThat(RequestActionResolver.resolve("DELETE", "/api/v1/audit/llm")).isNull();
  }

  @Test
  @DisplayName("evals 前缀只读：GET/HEAD → READ_AUDIT；非读方法未登记（fail-closed，同 audit）")
  void evalsPrefixFailsClosedForWrites() {
    assertAction("GET", "/api/v1/evals/export/trace/x", Action.READ_AUDIT);
    assertAction("HEAD", "/api/v1/evals/export/trace/x", Action.READ_AUDIT);
    assertThat(RequestActionResolver.resolve("POST", "/api/v1/evals/anything")).isNull();
    assertThat(RequestActionResolver.resolve("DELETE", "/api/v1/evals/export/trace/x")).isNull();
  }

  @Test
  @DisplayName("未登记路径返回 null（fail-closed）")
  void unmapped() {
    assertThat(RequestActionResolver.resolve("GET", "/api/v1/unknown-thing")).isNull();
    assertThat(RequestActionResolver.resolve("POST", "/api/v9/future")).isNull();
  }

  private static void assertSkip(String method, String path) {
    RequestActionResolver.Resolution r = RequestActionResolver.resolve(method, path);
    assertThat(r).isNotNull();
    assertThat(r.isSkip()).isTrue();
  }

  private static void assertAction(String method, String path, Action expected) {
    RequestActionResolver.Resolution r = RequestActionResolver.resolve(method, path);
    assertThat(r).isNotNull();
    assertThat(r.isSkip()).isFalse();
    assertThat(r.action()).isEqualTo(expected);
  }
}
