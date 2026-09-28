package io.oryxos.core.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.durable.ApprovalGrantContext;
import io.oryxos.core.policy.ApprovalPolicyDecision;
import io.oryxos.core.policy.ApprovalPolicyService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Direction I HITL：在团队任务真正 fan-out 前走与工具执行同一套 {@link ApprovalPolicyService}。
 *
 * <p>合成工具名 {@link #TOOL_NAME}，分类为 {@code TEAM_TASK}。默认 PASS_THROUGH 零行为变化；命中 REQUIRE_APPROVAL /
 * DENY 抛 {@link TeamTaskApprovalRequiredException}（本刀不做耐久挂起回放）。
 */
public final class TeamTaskApprovalGate {

  /** Synthetic tool name for approval rules ({@code oryxos.approval.rules[].tools}). */
  public static final String TOOL_NAME = "team_task";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ApprovalPolicyService policy;

  public TeamTaskApprovalGate(ApprovalPolicyService policy) {
    this.policy = policy == null ? ApprovalPolicyService.PASS_THROUGH : policy;
  }

  public void check(String agentName, String goal) {
    if (ApprovalGrantContext.grants(TOOL_NAME, null)) {
      return;
    }
    String agent = agentName == null || agentName.isBlank() ? "coordinator" : agentName.strip();
    String args = argumentsJson(goal);
    ApprovalPolicyDecision decision = policy.evaluate(agent, TOOL_NAME, args);
    if (decision.allowed()) {
      return;
    }
    policy.recordHit(null, agent, TOOL_NAME, decision);
    throw new TeamTaskApprovalRequiredException(decision);
  }

  static String argumentsJson(String goal) {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("goal", goal == null ? "" : goal);
    try {
      return MAPPER.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      return "{\"goal\":\"\"}";
    }
  }

  /** Package-visible for tests. */
  ApprovalPolicyService policy() {
    return Objects.requireNonNull(policy);
  }
}
