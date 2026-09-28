package io.oryxos.core.task;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.durable.ApprovalGrantContext;
import io.oryxos.core.policy.ApprovalOutcome;
import io.oryxos.core.policy.ApprovalPolicyDecision;
import io.oryxos.core.policy.ApprovalPolicyService;
import io.oryxos.core.policy.HighRiskActionType;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamTaskApprovalGateTest {

  @Test
  @DisplayName("PASS_THROUGH allows")
  void passThrough() {
    assertDoesNotThrow(
        () -> new TeamTaskApprovalGate(ApprovalPolicyService.PASS_THROUGH).check("c", "goal"));
  }

  @Test
  @DisplayName("REQUIRE_APPROVAL blocks with 需要人工审批")
  void requireApproval_blocks() {
    ApprovalPolicyService policy =
        (agent, tool, args) ->
            new ApprovalPolicyDecision(
                ApprovalOutcome.REQUIRE_APPROVAL,
                "1",
                "r1",
                HighRiskActionType.TEAM_TASK,
                List.of("ops"),
                60,
                "team spend");
    TeamTaskApprovalRequiredException ex =
        assertThrows(
            TeamTaskApprovalRequiredException.class,
            () -> new TeamTaskApprovalGate(policy).check("coordinator", "Ship"));
    assertTrue(ex.getMessage().startsWith("需要人工审批："));
    assertTrue(ex.getMessage().contains("team spend"));
  }

  @Test
  @DisplayName("grant context skips gate")
  void grantSkips() {
    AtomicInteger evals = new AtomicInteger();
    ApprovalPolicyService policy =
        (agent, tool, args) -> {
          evals.incrementAndGet();
          return new ApprovalPolicyDecision(
              ApprovalOutcome.REQUIRE_APPROVAL,
              "1",
              "r1",
              HighRiskActionType.TEAM_TASK,
              List.of(),
              null,
              "nope");
        };
    try (ApprovalGrantContext.Scope ignored =
        ApprovalGrantContext.open(
            new ApprovalGrantContext.Grant("cp-1", TeamTaskApprovalGate.TOOL_NAME, "tc-1"))) {
      assertDoesNotThrow(() -> new TeamTaskApprovalGate(policy).check("c", "g"));
    }
    assertEquals(0, evals.get());
  }
}
