package io.oryxos.core.cluster;

/**
 * 工作区变更通知（027）：管理写路径在文件落盘成功之后调用 bump 递增对应域版本号。 单机档装配为 NOOP（FR-010 零协调写零变化）；集群档装配为 CoordinationStore
 * 递增 + 本副本 owner。 通知失败不得影响写路径主链路——实现内部自吞异常（与 MetricsRecorder 同纪律）。
 */
@FunctionalInterface
public interface WorkspaceVersionNotifier {

  /** 单机档：零写入。 */
  WorkspaceVersionNotifier NOOP = domain -> {};

  /** 递增某域版本号（domain ∈ CoordinationStore.WORKSPACE_DOMAINS）。 */
  void bump(String domain);
}
