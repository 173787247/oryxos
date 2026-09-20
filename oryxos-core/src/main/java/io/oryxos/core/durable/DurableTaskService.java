package io.oryxos.core.durable;

import io.oryxos.core.agent.ExecutionContext;
import io.oryxos.core.policy.ApprovalHumanDecision;
import io.oryxos.core.policy.ApprovalPolicyDecision;
import io.oryxos.core.policy.ApprovalPolicyService;
import io.oryxos.core.provider.ToolCallRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 耐久任务状态机门面（043 / #465）：挂起写检查点、审批后恢复、幂等重试、重启后列出可恢复挂起态。
 *
 * <p>本刀不负责管理台/IM 回调（#466）；{@link #applyDecision} 供后续入口调用。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "CRLF_INJECTION_LOGS",
    justification = "Log args are checkpoint ids / tool names sanitized via sanitize().")
public final class DurableTaskService {

  private static final Logger LOG = LoggerFactory.getLogger(DurableTaskService.class);

  private final TaskCheckpointStore store;
  private final Clock clock;
  private final ApprovalPolicyService approvalPolicy;
  private final boolean enabled;

  public DurableTaskService(
      TaskCheckpointStore store,
      Clock clock,
      ApprovalPolicyService approvalPolicy,
      boolean enabled) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = clock == null ? Clock.systemUTC() : clock;
    this.approvalPolicy =
        approvalPolicy == null ? ApprovalPolicyService.PASS_THROUGH : approvalPolicy;
    this.enabled = enabled;
  }

  /** 兼容旧五参构造（忽略 audit 参数）。 */
  public DurableTaskService(
      TaskCheckpointStore store,
      Clock clock,
      ApprovalPolicyService approvalPolicy,
      Object ignoredAudit,
      boolean enabled) {
    this(store, clock, approvalPolicy, enabled);
  }

  public boolean enabled() {
    return enabled;
  }

  /** REQUIRE_APPROVAL 命中时落 WAITING_APPROVAL 检查点并返回挂起异常。幂等：同一 idempotencyKey 已存在则复用。 */
  public ApprovalSuspendedException suspendForApproval(
      String sessionId, String agentName, ToolCallRequest call, ApprovalPolicyDecision decision) {
    if (!enabled) {
      throw new IllegalStateException("durable-suspend 未启用");
    }
    Instant now = clock.instant();
    Long executionId = ExecutionContext.currentId();
    String toolCallId = call.id() == null || call.id().isBlank() ? call.name() : call.id();
    String idempotencyKey = buildIdempotencyKey(executionId, sessionId, toolCallId, 0);

    Optional<TaskCheckpoint> existing = store.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
      TaskCheckpoint cp = existing.get();
      return new ApprovalSuspendedException(
          cp.id(), cp.idempotencyKey(), "等待人工审批（复用检查点 " + cp.id() + "）");
    }

    String id = "cp-" + UUID.randomUUID().toString().replace("-", "");
    Integer ttl = decision == null ? null : decision.ttlSeconds();
    Instant expiresAt = ttl == null || ttl <= 0 ? null : now.plusSeconds(ttl.longValue());
    TaskCheckpoint created =
        new TaskCheckpoint(
            id,
            executionId,
            sessionId,
            agentName,
            DurableTaskState.WAITING_APPROVAL,
            idempotencyKey,
            TaskCheckpoint.KIND_PRE_TOOL_APPROVAL,
            call.name(),
            toolCallId,
            call.argumentsJson(),
            decision == null ? null : decision.policyVersion(),
            decision == null ? null : decision.ruleId(),
            0,
            null,
            ttl,
            expiresAt,
            now,
            now);
    store.save(created);
    LOG.info(
        "耐久挂起 WAITING_APPROVAL checkpoint={} tool={} key={}",
        sanitize(id),
        sanitize(call.name()),
        sanitize(idempotencyKey));
    return new ApprovalSuspendedException(
        id, idempotencyKey, decision == null ? "等待人工审批" : decision.reason());
  }

  /** 重启后仍处于 WAITING_APPROVAL 的检查点（验收：状态不丢）。 */
  public List<TaskCheckpoint> listWaitingApproval() {
    return store.listByState(DurableTaskState.WAITING_APPROVAL);
  }

  public Optional<TaskCheckpoint> findById(String checkpointId) {
    return store.findById(checkpointId);
  }

  public Optional<TaskCheckpoint> findByIdempotencyKey(String key) {
    return store.findByIdempotencyKey(key);
  }

  /**
   * 幂等重试：同一 key 已有检查点则返回之；否则基于失败检查点提升 attempt 新建 QUEUED→可再挂起。
   *
   * <p>对仍在 WAITING_APPROVAL / SUCCEEDED 的同 key 请求直接返回原快照（不重复副作用）。
   */
  public TaskCheckpoint retryWithIdempotencyKey(String idempotencyKey) {
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Optional<TaskCheckpoint> existing = store.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
      return existing.get();
    }
    throw new IllegalArgumentException("未知幂等键，无法重试: " + idempotencyKey);
  }

  /**
   * 从最近安全检查点恢复：批准则进入 RUNNING 并标记可回放；拒绝则 CANCELLED。同一决策对终态幂等。
   *
   * @return 更新后的检查点；调用方（#466）再用 {@link ApprovalGrantContext} + ToolExecutor 回放工具
   */
  public TaskCheckpoint applyDecision(
      String checkpointId, boolean approved, String actor, String comment) {
    TaskCheckpoint cp =
        store
            .findById(checkpointId)
            .orElseThrow(() -> new IllegalArgumentException("检查点不存在: " + checkpointId));

    if (cp.state().terminal()) {
      // 幂等：终态直接返回
      return cp;
    }
    if (cp.state() != DurableTaskState.WAITING_APPROVAL) {
      throw new IllegalStateException("检查点不可审批: state=" + cp.state());
    }

    Instant now = clock.instant();
    DurableTaskState next = approved ? DurableTaskState.RUNNING : DurableTaskState.CANCELLED;
    TaskCheckpoint updated = cp.withState(next, now, approved ? null : comment);
    Optional<TaskCheckpoint> moved =
        store.tryTransition(checkpointId, DurableTaskState.WAITING_APPROVAL, updated);
    if (moved.isEmpty()) {
      // 并发：再读一次
      return store.findById(checkpointId).orElse(cp);
    }

    approvalPolicy.recordHumanDecision(
        new ApprovalHumanDecision(
            cp.sessionId(),
            cp.agentName(),
            cp.toolName(),
            cp.policyVersion(),
            cp.ruleId(),
            approved,
            actor == null || actor.isBlank() ? "unknown" : actor,
            comment));

    return moved.get();
  }

  /** 回放完成：将 RUNNING 检查点收敛为 SUCCEEDED / FAILED（工具已执行后由调用方调用）。 */
  public TaskCheckpoint completeReplay(String checkpointId, boolean success, String error) {
    TaskCheckpoint cp =
        store
            .findById(checkpointId)
            .orElseThrow(() -> new IllegalArgumentException("检查点不存在: " + checkpointId));
    if (cp.state() == DurableTaskState.SUCCEEDED || cp.state() == DurableTaskState.FAILED) {
      return cp;
    }
    if (cp.state() != DurableTaskState.RUNNING) {
      throw new IllegalStateException("检查点不可完成回放: state=" + cp.state());
    }
    Instant now = clock.instant();
    DurableTaskState next = success ? DurableTaskState.SUCCEEDED : DurableTaskState.FAILED;
    TaskCheckpoint updated = cp.withState(next, now, success ? null : error);
    return store
        .tryTransition(checkpointId, DurableTaskState.RUNNING, updated)
        .orElseGet(() -> store.findById(checkpointId).orElse(updated));
  }

  /** 从检查点构造待回放的工具调用。 */
  public ToolCallRequest pendingCall(TaskCheckpoint cp) {
    return new ToolCallRequest(cp.toolCallId(), cp.toolName(), cp.argumentsJson());
  }

  public static String buildIdempotencyKey(
      Long executionId, String sessionId, String toolCallId, int attempt) {
    String exec = executionId == null ? "na" : Long.toString(executionId);
    String sid = sessionId == null || sessionId.isBlank() ? "na" : sessionId;
    String tc = toolCallId == null || toolCallId.isBlank() ? "na" : toolCallId;
    return "exec:" + exec + "|session:" + sid + "|toolCall:" + tc + "|attempt:" + attempt;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
