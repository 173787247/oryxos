package io.oryxos.core.cluster;

/**
 * 轮次互斥门面（026 跨模块契约）：AgentService.process 在既有进程内会话锁之内叠加本协调—— 认领「正在执行的一轮」而非会话永久归属。单机档用 {@link
 * #NOOP}（恒成功零存储访问， StreamListener.NOOP 惯例又一例）；集群档用 DbTurnCoordinator。
 */
public interface TurnCoordinator {

  /**
   * 认领会话轮次：被他人持有时阻塞轮询等待（poll-interval），超 wait-timeout 抛 {@link
   * TurnWaitTimeoutException}。返回的租约句柄随调用栈显式传递。
   */
  TurnLease acquire(String sessionId);

  /** 释放（幂等；只释放自己的持有）。 */
  void release(String sessionId, TurnLease lease);

  /** 单机档：恒成功、零存储访问、零线程登记。 */
  TurnCoordinator NOOP =
      new TurnCoordinator() {
        @Override
        public TurnLease acquire(String sessionId) {
          return TurnLease.NOOP;
        }

        @Override
        public void release(String sessionId, TurnLease lease) {
          // 单机档无租约
        }
      };
}
