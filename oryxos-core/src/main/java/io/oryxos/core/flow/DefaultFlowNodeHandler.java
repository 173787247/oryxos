package io.oryxos.core.flow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Default node handler (046 / #468): HUMAN/APPROVAL enter WAITING; AGENT/TOOL/NOTIFY use optional
 * per-node scripts or a type-level fallback that echoes inputs into declared outputs.
 */
public final class DefaultFlowNodeHandler implements FlowNodeHandler {

  private final Map<String, Function<Map<String, Object>, FlowNodeOutcome>> byNodeId;
  private final Map<FlowNodeType, Function<Map<String, Object>, FlowNodeOutcome>> byType;

  public DefaultFlowNodeHandler() {
    this(Map.of(), Map.of());
  }

  public DefaultFlowNodeHandler(
      Map<String, Function<Map<String, Object>, FlowNodeOutcome>> byNodeId,
      Map<FlowNodeType, Function<Map<String, Object>, FlowNodeOutcome>> byType) {
    this.byNodeId = byNodeId == null ? Map.of() : Map.copyOf(byNodeId);
    this.byType = byType == null ? Map.of() : Map.copyOf(byType);
  }

  @Override
  public FlowNodeOutcome execute(FlowNode node, Map<String, Object> inputs, FlowRun run) {
    Objects.requireNonNull(node, "node");
    Function<Map<String, Object>, FlowNodeOutcome> script = byNodeId.get(node.id());
    if (script != null) {
      return script.apply(inputs == null ? Map.of() : inputs);
    }
    Function<Map<String, Object>, FlowNodeOutcome> typeHandler = byType.get(node.type());
    if (typeHandler != null) {
      return typeHandler.apply(inputs == null ? Map.of() : inputs);
    }
    if (node.type() == FlowNodeType.HUMAN || node.type() == FlowNodeType.APPROVAL) {
      return FlowNodeOutcome.waiting(Map.of());
    }
    return FlowNodeOutcome.succeeded(echoOutputs(node, inputs));
  }

  private static Map<String, Object> echoOutputs(FlowNode node, Map<String, Object> inputs) {
    Map<String, Object> out = new LinkedHashMap<>();
    Map<String, Object> in = inputs == null ? Map.of() : inputs;
    for (String port : node.outputs().keySet()) {
      if (in.containsKey(port)) {
        out.put(port, in.get(port));
      } else if ("delivered".equals(port) && node.type() == FlowNodeType.NOTIFY) {
        out.put(port, Boolean.TRUE);
      } else if ("result".equals(port) && node.type() == FlowNodeType.TOOL) {
        out.put(port, "ok:" + (node.ref() == null ? node.id() : node.ref()));
      } else if ("message".equals(port) || "plan".equals(port) || "text".equals(port)) {
        Object topic = in.getOrDefault("topic", in.getOrDefault("change", in.get("text")));
        out.put(port, topic == null ? ("from:" + node.id()) : String.valueOf(topic));
      } else {
        out.put(port, in.getOrDefault(port, null));
      }
    }
    if (out.isEmpty() && !in.isEmpty()) {
      out.putAll(in);
    }
    return out;
  }
}
