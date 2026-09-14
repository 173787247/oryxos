package io.oryxos.core.cluster;

/**
 * 轮次租约句柄（026）：随 process 调用栈显式传递（不用 ThreadLocal——021 traceId 跨线程教训）。 {@link #stillHeld()}
 * 是写回前的硬闸：续租失败或被回收后必须放弃写回。
 */
public interface TurnLease {

  /** 写回前硬校验：本 owner 是否仍持有租约（一次 owner 条件查询/续租探测）。 */
  boolean stillHeld();

  /** 悬空轮留痕：把本轮 agent_executions id 关联进租约行（无 execution 的路径不调用）。 */
  void attachExecution(long agentExecutionId);

  /** NOOP 租约（单机档）：恒持有、零存储访问。 */
  TurnLease NOOP =
      new TurnLease() {
        @Override
        public boolean stillHeld() {
          return true;
        }

        @Override
        public void attachExecution(long agentExecutionId) {
          // 单机档无租约行
        }
      };
}
