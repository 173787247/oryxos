package io.oryxos.core.flow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One Flow node: type, optional ref, typed ports, explicit dependsOn, optional human timeout and
 * compensate target (#469).
 */
public record FlowNode(
    String id,
    FlowNodeType type,
    String ref,
    Map<String, FlowPort> inputs,
    Map<String, FlowPort> outputs,
    List<String> dependsOn,
    Integer timeoutSeconds,
    String compensate) {

  public FlowNode {
    id = Objects.requireNonNull(id, "id").strip();
    if (id.isEmpty()) {
      throw new IllegalArgumentException("node id 不能为空");
    }
    type = Objects.requireNonNull(type, "type");
    ref = ref == null || ref.isBlank() ? null : ref.strip();
    inputs = inputs == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(inputs));
    outputs = outputs == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(outputs));
    dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    if (timeoutSeconds != null && timeoutSeconds < 0) {
      throw new IllegalArgumentException("timeoutSeconds 不能为负: " + timeoutSeconds);
    }
    compensate = compensate == null || compensate.isBlank() ? null : compensate.strip();
  }

  /** Compatibility ctor without #469 fields. */
  public FlowNode(
      String id,
      FlowNodeType type,
      String ref,
      Map<String, FlowPort> inputs,
      Map<String, FlowPort> outputs,
      List<String> dependsOn) {
    this(id, type, ref, inputs, outputs, dependsOn, null, null);
  }
}
