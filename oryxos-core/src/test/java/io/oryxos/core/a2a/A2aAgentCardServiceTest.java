package io.oryxos.core.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class A2aAgentCardServiceTest {

  @Test
  @DisplayName("card maps each local agent to a skill")
  void card_mapsAgentsToSkills() {
    A2aAgentCardService svc =
        new A2aAgentCardService(
            A2aProperties.disabled(),
            () ->
                List.of(
                    new A2aAgentRef("writer", "drafts docs"),
                    new A2aAgentRef("researcher", "finds facts")));
    A2aAgentCard card = svc.card();
    assertEquals("OryxOS", card.name());
    assertEquals("http://localhost:8080/api/v1/a2a", card.url());
    assertEquals(2, card.skills().size());
    assertEquals("agent:researcher", card.skills().get(0).id());
    assertEquals("agent:writer", card.skills().get(1).id());
    assertTrue(card.skills().get(0).tags().contains("oryxos"));
  }

  @Test
  @DisplayName("empty catalog yields empty skills")
  void empty_catalog() {
    A2aAgentCard card = new A2aAgentCardService(A2aProperties.disabled(), List::of).card();
    assertTrue(card.skills().isEmpty());
  }
}
