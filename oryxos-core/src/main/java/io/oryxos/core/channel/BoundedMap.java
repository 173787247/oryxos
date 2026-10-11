package io.oryxos.core.channel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按容量淘汰的 Map：写入超过上限时丢掉最早的条目。
 *
 * <p>渠道层有一批「按外部标识记住一件事」的长驻表 —— 用户、会话、消息 id。这些 key 由对端决定，数量随时间只增不减， 长期运行的进程会一直持有历史上每一个来过的 key。
 *
 * <p>只按容量淘汰，不设 TTL：平台侧这些值的有效期没有可依据的口径，与其编一个时长，不如把内存这件事管住 —— 值被淘汰后 对应的一次回复失败，对端重发一条即可。
 *
 * <p>本类**不保证线程安全**。并发场景由调用方自行包装（渠道层惯用 {@code Collections.synchronizedMap}）。
 *
 * @param <K> 键类型
 * @param <V> 值类型
 */
public class BoundedMap<K, V> extends LinkedHashMap<K, V> {

  private static final long serialVersionUID = 1L;

  private final int maxEntries;

  /**
   * @param maxEntries 最多保留的条目数，必须为正
   */
  public BoundedMap(int maxEntries) {
    super(16, 0.75f, false);
    if (maxEntries <= 0) {
      throw new IllegalArgumentException("maxEntries 必须为正数: " + maxEntries);
    }
    this.maxEntries = maxEntries;
  }

  @Override
  protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
    return size() > maxEntries;
  }
}
