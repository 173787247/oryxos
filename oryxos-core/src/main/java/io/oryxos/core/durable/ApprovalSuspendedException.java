package io.oryxos.core.durable;

/**
 * 耐久挂起（043 / #465）：REQUIRE_APPROVAL 且开启 durable-suspend 时，ToolExecutor 抛出本异常以中止本轮循环并保留
 * WAITING_APPROVAL 状态（非 stub 拒绝）。
 */
public final class ApprovalSuspendedException extends RuntimeException {

  private final String checkpointId;
  private final String idempotencyKey;

  public ApprovalSuspendedException(String checkpointId, String idempotencyKey, String message) {
    super(message == null ? "等待人工审批" : message);
    this.checkpointId = checkpointId;
    this.idempotencyKey = idempotencyKey;
  }

  public String checkpointId() {
    return checkpointId;
  }

  public String idempotencyKey() {
    return idempotencyKey;
  }
}
