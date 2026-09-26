package io.oryxos.core.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/** A2A Agent Card (discovery document). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aAgentCard(
    String protocolVersion,
    String name,
    String description,
    String url,
    String preferredTransport,
    Provider provider,
    String version,
    Capabilities capabilities,
    List<String> defaultInputModes,
    List<String> defaultOutputModes,
    List<Skill> skills) {

  public A2aAgentCard {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(url, "url");
    preferredTransport =
        preferredTransport == null || preferredTransport.isBlank() ? "JSONRPC" : preferredTransport;
    defaultInputModes =
        defaultInputModes == null || defaultInputModes.isEmpty()
            ? List.of("text/plain")
            : List.copyOf(defaultInputModes);
    defaultOutputModes =
        defaultOutputModes == null || defaultOutputModes.isEmpty()
            ? List.of("text/plain")
            : List.copyOf(defaultOutputModes);
    skills = skills == null ? List.of() : List.copyOf(skills);
    capabilities = capabilities == null ? Capabilities.none() : capabilities;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Provider(String organization, String url) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Capabilities(
      boolean streaming, boolean pushNotifications, boolean stateTransitionHistory) {
    public static Capabilities none() {
      return new Capabilities(false, false, false);
    }

    /** Local message/stream (SSE) supported; push/history still off. */
    public static Capabilities withStreaming() {
      return new Capabilities(true, false, false);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Skill(
      String id, String name, String description, List<String> tags, List<String> examples) {
    public Skill {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      description = description == null ? "" : description;
      tags = tags == null || tags.isEmpty() ? List.of("oryxos", "agent") : List.copyOf(tags);
      examples = examples == null ? List.of() : List.copyOf(examples);
    }
  }
}
