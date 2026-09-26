package io.oryxos.tool.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.ToolExecutionContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SshProcessStarterTest {

  @AfterEach
  void clear() {
    ToolExecutionContext.clear();
  }

  @Test
  @DisplayName("missing host: fail loud")
  void missingHost_fails() {
    SshProcessStarter starter =
        new SshProcessStarter(new SshExecutionProperties("", "u", 22, "/tmp/key"));
    assertThrows(Exception.class, () -> starter.start(List.of("echo", "hi")));
  }

  @Test
  @DisplayName("builds ssh argv and sets execution backend")
  void buildsArgv() throws Exception {
    AtomicReference<List<String>> seen = new AtomicReference<>();
    ProcessStarter local =
        command -> {
          seen.set(List.copyOf(command));
          return new ProcessBuilder("true").start();
        };
    SshProcessStarter starter =
        new SshProcessStarter(
            new SshExecutionProperties("10.0.0.1", "agent", 2222, "/home/u/.ssh/id"), local);
    starter.start(List.of("uname", "-a"));
    List<String> argv = seen.get();
    assertEquals("ssh", argv.get(0));
    assertTrue(argv.contains("-i"));
    assertTrue(argv.contains("/home/u/.ssh/id"));
    assertTrue(argv.contains("2222"));
    assertTrue(argv.contains("agent@10.0.0.1"));
    assertTrue(argv.contains("uname"));
    assertEquals("ssh", ToolExecutionContext.executionBackend());
  }
}
