package io.oryxos.core.flow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Parsed Markdown Flow document (045 / #467). Immutable, Git-friendly source model. */
public record FlowDefinition(
    String apiVersion,
    String id,
    String version,
    String entry,
    Map<String, FlowNode> nodes,
    List<FlowEdge> edges,
    FlowBudget budget,
    FlowPermissions permissions,
    String description) {

  public static final String API_VERSION = "oryxos.flow/v1";

  public FlowDefinition {
    apiVersion = apiVersion == null ? "" : apiVersion.strip();
    id = id == null ? "" : id.strip();
    version = version == null || version.isBlank() ? "0" : version.strip();
    entry = entry == null ? "" : entry.strip();
    nodes = nodes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(nodes));
    edges = edges == null ? List.of() : List.copyOf(edges);
    budget = budget == null ? FlowBudget.EMPTY : budget;
    permissions = permissions == null ? FlowPermissions.EMPTY : permissions;
    description = description == null ? "" : description;
    Objects.requireNonNull(nodes);
  }
}
