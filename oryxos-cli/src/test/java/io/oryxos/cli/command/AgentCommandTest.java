package io.oryxos.cli.command;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.AgentLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentCommandTest {

  @TempDir Path root;

  @Test
  void readStringRejectsOversizedImportSource() throws Exception {
    Path source = root.resolve("expert.md");
    long over = AgentLoader.MAX_AGENT_MD_BYTES + 1;
    try (var out = Files.newOutputStream(source)) {
      byte[] chunk = new byte[1024 * 1024];
      java.util.Arrays.fill(chunk, (byte) 'x');
      long left = over;
      while (left > 0) {
        int n = (int) Math.min(left, chunk.length);
        out.write(chunk, 0, n);
        left -= n;
      }
    }

    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> AgentCommand.readString(source));
    assertTrue(ex.getMessage().contains("10 MiB"));
  }
}
