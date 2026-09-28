package io.oryxos.core.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.durable.ApprovalGrantContext;
import io.oryxos.core.durable.ApprovalSuspendedException;
import io.oryxos.core.durable.DurableTaskService;
import io.oryxos.core.policy.ApprovalPolicyDecision;
import io.oryxos.core.policy.ApprovalPolicyService;
import io.oryxos.core.provider.ToolCallRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Direction I HITL：团队任务 fan-out 前走 {@link ApprovalPolicyService}。
 *
 * <p>合成工具名 {@link #TOOL_NAME}（{@code TEAM_TASK}）。REQUIRE_APPROVAL 且耐久挂起启用时抛 {@link
 * ApprovalSuspendedException}；否则 REQUIRE/DENY 抛 {@link TeamTaskApprovalRequiredException}。
 */
public final class TeamTaskApprovalGate {

  /** Synthetic tool name for approval rules ({@code oryxos.approval.rules[].tools}). */
  public static final String TOOL_NAME = "team_task";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ApprovalPolicyService policy;
  private final DurableTaskService durableTasks;

  public TeamTaskApprovalGate(ApprovalPolicyService policy) {
    this(policy, null);
  }

  public TeamTaskApprovalGate(ApprovalPolicyService policy, DurableTaskService durableTasks) {
    this.policy = policy == null ? ApprovalPolicyService.PASS_THROUGH : policy;
    this.durableTasks = durableTasks;
  }

  public void check(String agentName, String goal) {
    if (ApprovalGrantContext.grants(TOOL_NAME, null)) {
      return;
    }
    String agent = agentName == null || agentName.isBlank() ? "coordinator" : agentName.strip();
    String args = argumentsJson(goal, agent);
    ApprovalPolicyDecision decision = policy.evaluate(agent, TOOL_NAME, args);
    if (decision.allowed()) {
      return;
    }
    policy.recordHit(null, agent, TOOL_NAME, decision);
    if (decision.requiresApproval() && durableTasks != null && durableTasks.enabled()) {
      String callId = "tt-" + UUID.randomUUID().toString().replace("-", "");
      ToolCallRequest call = new ToolCallRequest(callId, TOOL_NAME, args);
      // sessionId: team-task has no chat session; use synthetic key for checkpoint indexing.
      throw durableTasks.suspendForApproval("team-task:" + callId, agent, call, decision);
    }
    throw new TeamTaskApprovalRequiredException(decision);
  }

  static String argumentsJson(String goal, String coordinator) {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("goal", goal == null ? "" : goal);
    if (coordinator != null && !coordinator.isBlank()) {
      body.put("coordinator", coordinator.strip());
    }
    try {
      return MAPPER.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      return "{\"goal\":\"\"}";
    }
  }

  /** Parse goal from checkpoint arguments JSON (resume path). */
  public static String goalFromArgs(String argumentsJson) {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return "";
    }
    try {
      JsonNode n = MAPPER.readTree(argumentsJson);
      JsonNode g = n.get("goal");
      return g == null || g.isNull() ? "" : g.asText("");
    } catch (JsonProcessingException e) {
      return "";
    }
  }

  /** Parse optional coordinator override from checkpoint arguments JSON. */
  public static String coordinatorFromArgs(String argumentsJson) {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return null;
    }
    try {
      JsonNode n = MAPPER.readTree(argumentsJson);
      JsonNode c = n.get("coordinator");
      if (c == null || c.isNull()) {
        return null;
      }
      String v = c.asText("");
      return v.isBlank() ? null : v;
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  ApprovalPolicyService policy() {
    return Objects.requireNonNull(policy);
  }
}
