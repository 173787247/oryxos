package io.oryxos.cli.command;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.AgentLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileCommandTest {

  @TempDir Path root;

  @BeforeEach
  void setRoot() {
    System.setProperty("oryxos.root", root.toString());
  }

  @AfterEach
  void clearRoot() {
    System.clearProperty("oryxos.root");
  }

  @Test
  void showRejectsOversizedAgentMarkdown() throws Exception {
    Path agentDir = Files.createDirectories(root.resolve("agents").resolve("huge"));
    Path markdown = agentDir.resolve("AGENT.md");
    long over = AgentLoader.MAX_AGENT_MD_BYTES + 1;
    try (var out = Files.newOutputStream(markdown)) {
      byte[] chunk = new byte[1024 * 1024];
      java.util.Arrays.fill(chunk, (byte) 'x');
      long left = over;
      while (left > 0) {
        int n = (int) Math.min(left, chunk.length);
        out.write(chunk, 0, n);
        left -= n;
      }
    }

    ProfileCommand.ShowCommand show = new ProfileCommand.ShowCommand();
    show.name = "huge";
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, show::run);
    assertTrue(ex.getMessage().contains("10 MiB"));
  }
}
