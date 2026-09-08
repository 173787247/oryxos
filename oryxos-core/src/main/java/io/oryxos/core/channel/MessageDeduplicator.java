package io.oryxos.core.channel;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 入站事件去重器（017 R3）：按 {@code channelName:messageId} 判重，重复到达静默丢弃，保证用户只收到一条回答（SC-004）。
 *
 * <p>进程内有界结构：LRU 容量 + TTL 双重界限，防止长期运行内存无界增长。单实例部署语义下达标； 重启窗口的重复由 spec Edge Case 显式豁免（多副本共享去重属 v0.4
 * 分布式底座）。可丢失缓存不属业务状态，不违背「状态外置」。
 */
public class MessageDeduplicator {

  private static final int DEFAULT_CAPACITY = 5000;
  private static final Duration DEFAULT_TTL = Duration.ofHours(12);

  private final int capacity;
  private final Duration ttl;
  private final Clock clock;
  private final LinkedHashMap<String, Instant> seen;

  public MessageDeduplicator() {
    this(DEFAULT_CAPACITY, DEFAULT_TTL, Clock.systemUTC());
  }

  public MessageDeduplicator(int capacity, Duration ttl, Clock clock) {
    this.capacity = capacity;
    this.ttl = ttl;
    this.clock = clock;
    // accessOrder=false：按插入序淘汰最老条目即可，去重键不存在"热点续期"语义
    this.seen =
        new LinkedHashMap<>(16, 0.75f, false) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
            return size() > MessageDeduplicator.this.capacity;
          }
        };
  }

  /**
   * 原子判重：首次出现返回 true 并登记；重复（未过期）返回 false。
   *
   * @param key 去重键（约定 {@code channelName + ":" + messageId}）
   */
  public synchronized boolean markIfFirst(String key) {
    Instant now = clock.instant();
    evictExpired(now);
    Instant existing = seen.get(key);
    if (existing != null) {
      return false;
    }
    seen.put(key, now);
    return true;
  }

  /** 从最老条目起清掉已过 TTL 的登记（插入序即时间序，遇到未过期即可停）。 */
  private void evictExpired(Instant now) {
    Instant cutoff = now.minus(ttl);
    Iterator<Map.Entry<String, Instant>> it = seen.entrySet().iterator();
    while (it.hasNext()) {
      if (it.next().getValue().isBefore(cutoff)) {
        it.remove();
      } else {
        break;
      }
    }
  }
}
