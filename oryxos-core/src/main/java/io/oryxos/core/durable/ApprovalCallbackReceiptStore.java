package io.oryxos.core.durable;

/**
 * IM 审批回调收据（044 / #466）：按 channel+callbackId 去重，重复回调安全。
 *
 * <p>{@link #tryClaim} 返回 true 表示首次认领（可继续决策）；false 表示已处理过。
 */
public interface ApprovalCallbackReceiptStore {

  /**
   * @param channel feishu / wecom / admin 等
   * @param callbackId 渠道侧幂等键
   * @param checkpointId 关联检查点（可空，审计用）
   * @return true=首次认领
   */
  boolean tryClaim(String channel, String callbackId, String checkpointId);

  boolean alreadySeen(String channel, String callbackId);
}
