package io.oryxos.cli;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.oryxos.tool.interaction.ConsoleUserInteraction;
import io.oryxos.tool.interaction.UnsupportedUserInteraction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** serve/gateway vs chat 的 ask_user 交互装配选择。 */
class UserInteractionWiringTest {

  @Test
  @DisplayName("非 Servlet web（chat / WebApplicationType.NONE）→ ConsoleUserInteraction")
  void nonWebUsesConsole() {
    assertInstanceOf(ConsoleUserInteraction.class, OryxOsRuntime.resolveUserInteraction(false));
  }

  @Test
  @DisplayName("Servlet web（serve/gateway）→ UnsupportedUserInteraction")
  void servletWebUsesUnsupported() {
    assertInstanceOf(UnsupportedUserInteraction.class, OryxOsRuntime.resolveUserInteraction(true));
  }
}
