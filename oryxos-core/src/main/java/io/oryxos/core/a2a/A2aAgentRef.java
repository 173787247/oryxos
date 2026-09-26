package io.oryxos.core.a2a;

/** Local agent identity for A2A Agent Card skills. */
public record A2aAgentRef(String name, String description) {
  public A2aAgentRef {
    name = name == null ? "" : name.strip();
    description = description == null ? "" : description.strip();
  }
}
