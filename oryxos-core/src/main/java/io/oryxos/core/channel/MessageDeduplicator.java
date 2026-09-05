package io.oryxos.core.channel;

/**
 * 入站事件去重契约（017 R3 / 026 接口化）：按 {@code channelName:messageId} 判重，重复到达静默 丢弃保证恰好一答。单机档 {@link
 * InMemoryMessageDeduplicator}；多副本档 SharedReceiptDeduplicator （回执落共享库，跨副本生效——飞书超时重推落到另一副本的场景由此收口）。
 */
public interface MessageDeduplicator {

  /**
   * 原子判重：首次出现返回 true 并登记；重复返回 false。
   *
   * @param key 去重键（约定 {@code channelName + ":" + messageId}）
   */
  boolean markIfFirst(String key);
}
