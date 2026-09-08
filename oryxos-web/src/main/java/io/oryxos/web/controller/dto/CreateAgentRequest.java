package io.oryxos.web.controller.dto;

import java.util.List;

/** POST /agents request: Agent metadata, selected provider/model, and initial bindings. */
public record CreateAgentRequest(
    String name,
    String description,
    String provider,
    String model,
    List<String> skillBindings,
    List<String> knowledgeBindings) {
  public CreateAgentRequest {
    skillBindings = skillBindings == null ? List.of() : List.copyOf(skillBindings);
    knowledgeBindings = knowledgeBindings == null ? List.of() : List.copyOf(knowledgeBindings);
  }

  public CreateAgentRequest(
      String name, String description, String provider, String model, List<String> skillBindings) {
    this(name, description, provider, model, skillBindings, List.of());
  }

  public CreateAgentRequest(String name, String description) {
    this(name, description, null, null, List.of(), List.of());
  }
}
