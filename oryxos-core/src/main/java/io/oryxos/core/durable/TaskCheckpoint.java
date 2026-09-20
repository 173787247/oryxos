package io.oryxos.core.durable;

import java.time.Instant;

/**
 * 耐久任务检查点（043 / #465 + 044 / #466）：挂起/恢复/重试的持久化快照；含审批时效字段供过期处理。
 *
 * @param id 检查点主键
 * @param executionId 关联 {@code agent_executions.id}（可空）
 * @param sessionId 会话
 * @param agentName Agent
 * @param state 状态机当前态
 * @param idempotencyKey 重试幂等键（唯一）
 * @param checkpointKind 检查点种类（如 PRE_TOOL_APPROVAL）
 * @param toolName 待执行工具
 * @param toolCallId 工具调用 id
 * @param argumentsJson 工具入参（审批时可改）
 * @param policyVersion 审批策略版本
 * @param ruleId 命中规则
 * @param attempt 尝试次数（从 0 起）
 * @param lastError 最近错误（可空）
 * @param ttlSeconds 审批有效期秒（可空）
 * @param expiresAt 过期时刻（可空；有则决定前校验）
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record TaskCheckpoint(
    String id,
    Long executionId,
    String sessionId,
    String agentName,
    DurableTaskState state,
    String idempotencyKey,
    String checkpointKind,
    String toolName,
    String toolCallId,
    String argumentsJson,
    String policyVersion,
    String ruleId,
    int attempt,
    String lastError,
    Integer ttlSeconds,
    Instant expiresAt,
    Instant createdAt,
    Instant updatedAt) {

  public static final String KIND_PRE_TOOL_APPROVAL = "PRE_TOOL_APPROVAL";

  public TaskCheckpoint withState(DurableTaskState next, Instant at) {
    return withState(next, at, lastError);
  }

  public TaskCheckpoint withState(DurableTaskState next, Instant at, String error) {
    return new TaskCheckpoint(
        id,
        executionId,
        sessionId,
        agentName,
        next,
        idempotencyKey,
        checkpointKind,
        toolName,
        toolCallId,
        argumentsJson,
        policyVersion,
        ruleId,
        attempt,
        error,
        ttlSeconds,
        expiresAt,
        createdAt,
        at);
  }

  public TaskCheckpoint withAttempt(int nextAttempt, Instant at) {
    return new TaskCheckpoint(
        id,
        executionId,
        sessionId,
        agentName,
        state,
        idempotencyKey,
        checkpointKind,
        toolName,
        toolCallId,
        argumentsJson,
        policyVersion,
        ruleId,
        nextAttempt,
        lastError,
        ttlSeconds,
        expiresAt,
        createdAt,
        at);
  }

  /** 审批人修改待执行参数（仍须处于 WAITING_APPROVAL）。 */
  public TaskCheckpoint withArguments(String nextArgs, Instant at) {
    return new TaskCheckpoint(
        id,
        executionId,
        sessionId,
        agentName,
        state,
        idempotencyKey,
        checkpointKind,
        toolName,
        toolCallId,
        nextArgs,
        policyVersion,
        ruleId,
        attempt,
        lastError,
        ttlSeconds,
        expiresAt,
        createdAt,
        at);
  }

  public boolean expiredAt(Instant now) {
    return expiresAt != null && now != null && !now.isBefore(expiresAt);
  }
}
