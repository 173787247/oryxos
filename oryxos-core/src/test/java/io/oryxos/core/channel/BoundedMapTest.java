package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoundedMapTest {

  @Test
  @DisplayName("超过上限时丢掉最早的条目，表停在上限内")
  void evictsOldestBeyondCapacity() {
    Map<String, String> map = new BoundedMap<>(3);
    map.put("a", "1");
    map.put("b", "2");
    map.put("c", "3");
    map.put("d", "4");
    assertEquals(3, map.size());
    assertNull(map.get("a"), "最早的条目应被丢掉");
    assertEquals("4", map.get("d"));
  }

  @Test
  @DisplayName("重写已有的键不改变它的位置，也不因此触发淘汰")
  void rewritingAKeyKeepsItsPlace() {
    Map<String, String> map = new BoundedMap<>(2);
    map.put("a", "1");
    map.put("b", "2");
    map.put("a", "1b");
    map.put("c", "3");
    assertEquals(2, map.size());
    assertNull(map.get("a"), "a 仍是最早插入的那个，应被淘汰");
    assertEquals("2", map.get("b"));
    assertEquals("3", map.get("c"));
  }

  @Test
  @DisplayName("上限必须为正")
  void rejectsNonPositiveCapacity() {
    assertThrows(IllegalArgumentException.class, () -> new BoundedMap<>(0));
    assertThrows(IllegalArgumentException.class, () -> new BoundedMap<>(-1));
  }

  @Test
  @DisplayName("按渠道层惯例包一层 synchronizedMap 后仍按容量淘汰")
  void worksBehindSynchronizedMap() {
    Map<String, String> map = Collections.synchronizedMap(new BoundedMap<>(2));
    map.put("a", "1");
    map.put("b", "2");
    map.put("c", "3");
    assertEquals(2, map.size());
    assertNull(map.get("a"));
  }
}
