package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 017 R3：去重缓存的判重 / LRU 淘汰 / TTL 过期语义。 */
class MessageDeduplicatorTest {

  /** 可手动拨动的测试时钟。 */
  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-08-25T00:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }

  @Test
  @DisplayName("首次登记返回 true，重复返回 false")
  void firstThenDuplicate() {
    MessageDeduplicator dedup = new MessageDeduplicator();
    assertTrue(dedup.markIfFirst("chan:m-1"));
    assertFalse(dedup.markIfFirst("chan:m-1"));
    assertTrue(dedup.markIfFirst("chan:m-2"));
  }

  @Test
  @DisplayName("超出容量时最老条目被 LRU 淘汰，可再次登记")
  void lruEviction() {
    MessageDeduplicator dedup = new MessageDeduplicator(3, Duration.ofHours(12), Clock.systemUTC());
    assertTrue(dedup.markIfFirst("k1"));
    assertTrue(dedup.markIfFirst("k2"));
    assertTrue(dedup.markIfFirst("k3"));
    assertTrue(dedup.markIfFirst("k4")); // k1 被淘汰
    assertTrue(dedup.markIfFirst("k1"));
    assertFalse(dedup.markIfFirst("k4"));
  }

  @Test
  @DisplayName("过 TTL 的登记过期，可再次登记")
  void ttlExpiry() {
    MutableClock clock = new MutableClock();
    MessageDeduplicator dedup = new MessageDeduplicator(100, Duration.ofHours(12), clock);
    assertTrue(dedup.markIfFirst("k1"));
    clock.advance(Duration.ofHours(11));
    assertFalse(dedup.markIfFirst("k1")); // 未过期
    clock.advance(Duration.ofHours(2));
    assertTrue(dedup.markIfFirst("k1")); // 已过期
  }
}
