package io.oryxos.core.cluster;

/** 轮次租约在执行期间被回收（026 fencing 硬闸）：假死/超长轮次的持有权已被健康副本抢占——本方 必须放弃写回（会话历史以接管方为准），本轮按失败落审计。 */
public class TurnFencedException extends RuntimeException {

  public TurnFencedException(String sessionId) {
    super("轮次租约已被回收（副本失联判定），放弃写回: " + sessionId);
  }
}
