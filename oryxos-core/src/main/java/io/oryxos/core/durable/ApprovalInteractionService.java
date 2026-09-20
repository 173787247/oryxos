package io.oryxos.core.durable;

import io.oryxos.core.ToolResult;
import io.oryxos.core.policy.ApprovalAuditKind;
import io.oryxos.core.policy.ApprovalAuditRecorder;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 管理台 / IM 统一审批交互门面（044 / #466）：列出 WAITING_APPROVAL、approve/deny/改参、过期与重复回调安全，并委托 {@link
 * DurableTaskReplay#resume} 恢复执行。
 */
public final class ApprovalInteractionService {

  private final DurableTaskService tasks;
  private final DurableTaskReplay replay;
  private final TaskCheckpointStore store;
  private final ApprovalCallbackReceiptStore callbacks;
  private final ApprovalAuditRecorder audit;
  private final Clock clock;
  private final boolean enabled;

  public ApprovalInteractionService(
      DurableTaskService tasks,
      DurableTaskReplay replay,
      TaskCheckpointStore store,
      ApprovalCallbackReceiptStore callbacks,
      ApprovalAuditRecorder audit,
      Clock clock,
      boolean enabled) {
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.replay = Objects.requireNonNull(replay, "replay");
    this.store = Objects.requireNonNull(store, "store");
    this.callbacks = callbacks == null ? new InMemoryApprovalCallbackReceiptStore() : callbacks;
    this.audit = audit == null ? ApprovalAuditRecorder.NOOP : audit;
    this.clock = clock == null ? Clock.systemUTC() : clock;
    this.enabled = enabled;
  }

  public boolean enabled() {
    return enabled;
  }

  /** 待审批列表（含是否已过期标记，供管理台展示）。 */
  public List<PendingApprovalView> listWaiting() {
    Instant now = clock.instant();
    List<PendingApprovalView> out = new ArrayList<>();
    for (TaskCheckpoint cp : tasks.listWaitingApproval()) {
      out.add(PendingApprovalView.from(cp, now));
    }
    return List.copyOf(out);
  }

  public Optional<ApprovalDetailView> detail(String checkpointId) {
    return tasks.findById(checkpointId).map(cp -> ApprovalDetailView.from(cp, clock.instant()));
  }

  /** 管理台决策：可选改参 → 过期则 TIMEOUT_DENIED + CANCELLED；否则 {@link DurableTaskReplay#resume}。终态重复调用幂等。 */
  public DecisionOutcome decide(DecisionRequest request) {
    Objects.requireNonNull(request, "request");
    String checkpointId = requireNonBlank(request.checkpointId(), "checkpointId");
    TaskCheckpoint cp =
        tasks
            .findById(checkpointId)
            .orElseThrow(() -> new IllegalArgumentException("检查点不存在: " + checkpointId));

    if (cp.state().terminal()) {
      return DecisionOutcome.duplicate(cp, null);
    }

    Instant now = clock.instant();
    if (cp.state() == DurableTaskState.WAITING_APPROVAL && cp.expiredAt(now)) {
      return expire(cp, request.actor(), request.comment());
    }

    if (request.argumentsJson() != null
        && cp.state() == DurableTaskState.WAITING_APPROVAL
        && !Objects.equals(request.argumentsJson(), cp.argumentsJson())) {
      TaskCheckpoint edited = cp.withArguments(request.argumentsJson(), now);
      store.save(edited);
    }

    DurableTaskReplay.ReplayOutcome outcome =
        replay.resume(checkpointId, request.approved(), request.actor(), request.comment());
    return DecisionOutcome.fromReplay(outcome);
  }

  /**
   * 飞书/企微统一回调薄入口：按 {@code callbackId} 去重；载荷与管理台决策同形。
   *
   * @return 决策结果；重复 callbackId 返回 duplicate=true 且不二次执行
   */
  public DecisionOutcome handleCallback(
      String channel, String callbackId, DecisionRequest request) {
    String ch = requireNonBlank(channel, "channel");
    String cb = requireNonBlank(callbackId, "callbackId");
    if (!callbacks.tryClaim(ch, cb, request == null ? null : request.checkpointId())) {
      TaskCheckpoint cp =
          request == null || request.checkpointId() == null
              ? null
              : tasks.findById(request.checkpointId()).orElse(null);
      return DecisionOutcome.duplicate(cp, "duplicate callback: " + ch + "/" + cb);
    }
    return decide(request);
  }

  private DecisionOutcome expire(TaskCheckpoint cp, String actor, String comment) {
    Instant now = clock.instant();
    String who = actor == null || actor.isBlank() ? "system" : actor;
    String reason =
        comment == null || comment.isBlank() ? "approval expired at " + cp.expiresAt() : comment;

    TaskCheckpoint cancelled = cp.withState(DurableTaskState.CANCELLED, now, "EXPIRED: " + reason);
    Optional<TaskCheckpoint> moved =
        store.tryTransition(cp.id(), DurableTaskState.WAITING_APPROVAL, cancelled);
    TaskCheckpoint finalCp = moved.orElseGet(() -> tasks.findById(cp.id()).orElse(cancelled));

    if (moved.isPresent()) {
      audit.record(
          new ApprovalAuditRecorder.ApprovalAuditEvent(
              ApprovalAuditKind.TIMEOUT_DENIED,
              cp.sessionId(),
              cp.agentName(),
              cp.toolName(),
              null,
              cp.policyVersion(),
              cp.ruleId(),
              who,
              reason,
              cp.ttlSeconds()));
    }
    return new DecisionOutcome(finalCp, null, false, true, false, null);
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " required");
    }
    return value;
  }

  /** 管理台/回调决策请求。 */
  public record DecisionRequest(
      String checkpointId, boolean approved, String actor, String comment, String argumentsJson) {}

  /** 待审批行视图。 */
  public record PendingApprovalView(
      String checkpointId,
      String sessionId,
      String agentName,
      String toolName,
      String toolCallId,
      String argumentsJson,
      String policyVersion,
      String ruleId,
      Integer ttlSeconds,
      Instant expiresAt,
      boolean expired,
      Instant createdAt) {

    static PendingApprovalView from(TaskCheckpoint cp, Instant now) {
      return new PendingApprovalView(
          cp.id(),
          cp.sessionId(),
          cp.agentName(),
          cp.toolName(),
          cp.toolCallId(),
          cp.argumentsJson(),
          cp.policyVersion(),
          cp.ruleId(),
          cp.ttlSeconds(),
          cp.expiresAt(),
          cp.expiredAt(now),
          cp.createdAt());
    }
  }

  /** 详情（审批人/意见/时效/执行结果回放的最小面）。 */
  public record ApprovalDetailView(
      String checkpointId,
      String state,
      String sessionId,
      String agentName,
      String toolName,
      String toolCallId,
      String argumentsJson,
      String policyVersion,
      String ruleId,
      Integer ttlSeconds,
      Instant expiresAt,
      boolean expired,
      String lastError,
      Instant createdAt,
      Instant updatedAt) {

    static ApprovalDetailView from(TaskCheckpoint cp, Instant now) {
      return new ApprovalDetailView(
          cp.id(),
          cp.state().name(),
          cp.sessionId(),
          cp.agentName(),
          cp.toolName(),
          cp.toolCallId(),
          cp.argumentsJson(),
          cp.policyVersion(),
          cp.ruleId(),
          cp.ttlSeconds(),
          cp.expiresAt(),
          cp.expiredAt(now),
          cp.lastError(),
          cp.createdAt(),
          cp.updatedAt());
    }
  }

  /**
   * @param expired 因过期取消
   * @param duplicate 终态或重复回调
   */
  public record DecisionOutcome(
      TaskCheckpoint checkpoint,
      ToolResult toolResult,
      boolean alreadyDone,
      boolean expired,
      boolean duplicate,
      String message) {

    static DecisionOutcome fromReplay(DurableTaskReplay.ReplayOutcome outcome) {
      return new DecisionOutcome(
          outcome.checkpoint(),
          outcome.toolResult(),
          outcome.alreadyDone(),
          false,
          outcome.alreadyDone(),
          null);
    }

    static DecisionOutcome duplicate(TaskCheckpoint cp, String message) {
      return new DecisionOutcome(cp, null, true, false, true, message);
    }
  }
}
