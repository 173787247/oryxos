package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.a2a.A2aRemoteClient;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class A2aSendToolsTest {

  @Test
  @DisplayName("disabled gate")
  void disabled() {
    A2aRemoteClient client = A2aRemoteClient.create(Duration.ofSeconds(1), uri -> true);
    A2aSendTools tools = new A2aSendTools(client, false);
    assertThrows(IllegalStateException.class, () -> tools.a2aSend("http://x", "a", "m"));
  }

  @Test
  @DisplayName("blank args rejected")
  void blanks() {
    A2aRemoteClient client = A2aRemoteClient.create(Duration.ofSeconds(1), uri -> true);
    A2aSendTools tools = new A2aSendTools(client, true);
    assertThrows(IllegalArgumentException.class, () -> tools.a2aSend(" ", "a", "m"));
    assertThrows(IllegalArgumentException.class, () -> tools.a2aSend("http://x", "", "m"));
  }

  @Test
  @DisplayName("host deny surfaces as IllegalStateException")
  void hostDeny() {
    A2aRemoteClient client = A2aRemoteClient.create(Duration.ofSeconds(1), uri -> false);
    A2aSendTools tools = new A2aSendTools(client, true);
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> tools.a2aSend("http://evil.example", "writer", "hi"));
    assertTrue(ex.getMessage().contains("a2a_send failed"));
    assertTrue(ex.getCause() instanceof IllegalArgumentException);
  }

  @Test
  @DisplayName("resolveCardUri used by send path contract")
  void cardUriContract() {
    assertEquals(
        URI.create("http://h/.well-known/agent-card.json"),
        A2aRemoteClient.resolveCardUri(URI.create("http://h")));
  }
}
