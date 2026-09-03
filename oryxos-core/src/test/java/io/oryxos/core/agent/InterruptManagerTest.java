package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InterruptManagerTest {

  @Test
  @DisplayName("interrupt / isInterrupted / clear 闭环")
  void interruptClearRoundTrip() {
    InterruptManager mgr = new InterruptManager();
    assertFalse(mgr.isInterrupted("s1"));
    mgr.interrupt("s1");
    assertTrue(mgr.isInterrupted("s1"));
    assertFalse(mgr.isInterrupted("s2"));
    mgr.clear("s1");
    assertFalse(mgr.isInterrupted("s1"));
  }

  @Test
  @DisplayName("null sessionId 安全忽略")
  void nullSafe() {
    InterruptManager mgr = new InterruptManager();
    mgr.interrupt(null);
    assertFalse(mgr.isInterrupted(null));
    mgr.clear(null);
    assertEquals(false, mgr.isInterrupted(""));
  }
}
