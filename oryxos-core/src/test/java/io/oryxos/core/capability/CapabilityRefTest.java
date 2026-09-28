package io.oryxos.core.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CapabilityRefTest {

  @Test
  @DisplayName("parse kind:name and kind/name")
  void parse() {
    assertEquals(CapabilityKind.SKILL, CapabilityRef.parse("skill:summarizer").kind());
    assertEquals("summarizer", CapabilityRef.parse("skill:summarizer").name());
    assertEquals("tool:web_search", CapabilityRef.parse("tool/web_search").describe());
  }

  @Test
  @DisplayName("reject bare name")
  void rejectBare() {
    assertThrows(IllegalArgumentException.class, () -> CapabilityRef.parse("web_search"));
  }
}
