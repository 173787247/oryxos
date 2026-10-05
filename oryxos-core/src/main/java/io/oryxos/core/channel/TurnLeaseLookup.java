package io.oryxos.core.channel;

/**
 * 只读查询：某个会话的推理 turn 是否正被本副本以外的持有者占着。
 *
 * <p>集群档下推理可能发生在另一个副本上，而 {@link ActiveRunRegistry} 是进程内的 ——
 * 本副本看不到那条运行。这个窄接口只暴露「查」这一件事，避免把整个集群协调面引进入站链路； 单机档用 {@link #NONE} 即可。
 */
@FunctionalInterface
public interface TurnLeaseLookup {

  /** 未接线或单机档时的默认实现：永远回答「没有别人在跑」。 */
  TurnLeaseLookup NONE = sessionId -> false;

  /**
   * @param sessionId 会话 id（由 {@code channel + user + profile} 三元组决定，与 chatId 无关）
   * @return true 表示该会话的 turn 租约存在，且持有者是【本副本以外】的副本
   */
  boolean heldByAnotherReplica(String sessionId);
}
