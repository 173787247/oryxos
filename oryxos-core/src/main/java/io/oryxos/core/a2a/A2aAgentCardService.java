package io.oryxos.core.a2a;

import io.oryxos.core.a2a.A2aAgentCard.Capabilities;
import io.oryxos.core.a2a.A2aAgentCard.Provider;
import io.oryxos.core.a2a.A2aAgentCard.Skill;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Builds an A2A Agent Card from local agents (one skill per agent). Discovery only — does not send
 * A2A messages.
 */
public final class A2aAgentCardService {

  private final A2aProperties properties;
  private final Supplier<List<A2aAgentRef>> agents;

  public A2aAgentCardService(A2aProperties properties, Supplier<List<A2aAgentRef>> agents) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.agents = Objects.requireNonNull(agents, "agents");
  }

  public A2aAgentCard card() {
    List<A2aAgentRef> refs = agents.get();
    if (refs == null) {
      refs = List.of();
    }
    List<Skill> skills = new ArrayList<>();
    refs.stream()
        .sorted(Comparator.comparing(A2aAgentRef::name, String.CASE_INSENSITIVE_ORDER))
        .forEach(
            ref -> {
              if (ref.name().isEmpty()) {
                return;
              }
              String desc =
                  ref.description().isBlank() ? "OryxOS agent: " + ref.name() : ref.description();
              skills.add(
                  new Skill(
                      "agent:" + ref.name(),
                      ref.name(),
                      desc,
                      List.of("oryxos", "agent", "local"),
                      List.of()));
            });
    return new A2aAgentCard(
        properties.protocolVersion(),
        properties.name(),
        properties.description(),
        properties.a2aServiceUrl(),
        "JSONRPC",
        new Provider("oryx-labs", "https://github.com/oryx-labs/oryxos"),
        properties.version(),
        Capabilities.none(),
        List.of("text/plain"),
        List.of("text/plain"),
        skills);
  }
}
