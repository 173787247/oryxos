package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.tool.sandbox.ExecutionBackendProperties;
import io.oryxos.tool.sandbox.LocalProcessStarter;
import io.oryxos.tool.sandbox.PermissiveSandbox;
import io.oryxos.tool.sandbox.ProcessStarter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExecuteCodeToolsTest {

  @Test
  @DisplayName("disabled: fail loud")
  void disabled_fails() {
    ExecuteCodeTools tools =
        new ExecuteCodeTools(
            new PermissiveSandbox(),
            new LocalProcessStarter(),
            new ExecutionBackendProperties("docker", "python:3.12-alpine", null, null, null, null),
            false);
    assertThrows(IllegalStateException.class, () -> tools.executeCode("python3", "print(1)"));
  }

  @Test
  @DisplayName("non-docker backend: fail loud, no local fallback")
  void localBackend_fails() {
    ExecuteCodeTools tools =
        new ExecuteCodeTools(
            new PermissiveSandbox(),
            new LocalProcessStarter(),
            new ExecutionBackendProperties("local", "", null, null, null, null),
            true);
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> tools.executeCode("python3", "print(1)"));
    assertTrue(ex.getMessage().contains("docker"));
  }

  @Test
  @DisplayName("docker backend: runs via ProcessStarter argv python3 -c")
  void dockerBackend_invokesStarter() throws Exception {
    AtomicReference<List<String>> seen = new AtomicReference<>();
    ProcessStarter starter =
        command -> {
          seen.set(List.copyOf(command));
          return new ProcessBuilder("python3", "-c", "print('ok')").start();
        };
    ExecuteCodeTools tools =
        new ExecuteCodeTools(
            new PermissiveSandbox(),
            starter,
            new ExecutionBackendProperties("docker", "python:3.12-alpine", null, null, null, null),
            true);
    String out = tools.executeCode("python3", "print('ok')");
    assertTrue(out.contains("exit=0"));
    assertTrue(out.contains("ok"));
    assertTrue(seen.get().equals(List.of("python3", "-c", "print('ok')")));
  }
}
