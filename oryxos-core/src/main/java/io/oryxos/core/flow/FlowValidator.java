package io.oryxos.core.flow;

import io.oryxos.core.flow.FlowPort.WireRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Static Flow graph validation (045 / #467): missing refs, type mismatch, cycles.
 *
 * <p>Does not execute nodes; safe to run in CI before any durable engine (#468).
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "IMPROPER_UNICODE",
    justification = "Flow DSL keywords are ASCII; Locale.ROOT fold is intentional.")
public final class FlowValidator {

  private FlowValidator() {}

  public static List<FlowDiagnostic> validate(FlowDefinition flow) {
    Objects.requireNonNull(flow, "flow");
    List<FlowDiagnostic> out = new ArrayList<>();

    if (!FlowDefinition.API_VERSION.equals(flow.apiVersion())) {
      out.add(
          FlowDiagnostic.error(
              "INVALID_API_VERSION",
              "apiVersion",
              "期望 apiVersion=" + FlowDefinition.API_VERSION + "，实际=" + flow.apiVersion()));
    }
    if (flow.id().isEmpty()) {
      out.add(FlowDiagnostic.error("MISSING_ID", "id", "Flow id 不能为空"));
    }
    if (flow.nodes().isEmpty()) {
      out.add(FlowDiagnostic.error("EMPTY_NODES", "nodes", "Flow 至少需要一个 node"));
      return List.copyOf(out);
    }
    if (flow.entry().isEmpty()) {
      out.add(FlowDiagnostic.error("MISSING_ENTRY", "entry", "Flow entry 不能为空"));
    } else if (!flow.nodes().containsKey(flow.entry())) {
      out.add(
          FlowDiagnostic.error("UNKNOWN_ENTRY", "entry", "entry 引用了不存在的 node: " + flow.entry()));
    }

    checkEdges(flow, out);
    checkDependsAndWires(flow, out);
    checkCycles(flow, out);
    return List.copyOf(out);
  }

  public static boolean hasErrors(List<FlowDiagnostic> diagnostics) {
    return diagnostics.stream().anyMatch(FlowDiagnostic::isError);
  }

  private static void checkEdges(FlowDefinition flow, List<FlowDiagnostic> out) {
    int i = 0;
    for (FlowEdge edge : flow.edges()) {
      String path = "edges[" + i + "]";
      if (!flow.nodes().containsKey(edge.from())) {
        out.add(
            FlowDiagnostic.error(
                "UNKNOWN_NODE_REF", path + ".from", "edge.from 引用了不存在的 node: " + edge.from()));
      }
      if (!flow.nodes().containsKey(edge.to())) {
        out.add(
            FlowDiagnostic.error(
                "UNKNOWN_NODE_REF", path + ".to", "edge.to 引用了不存在的 node: " + edge.to()));
      }
      i++;
    }
  }

  private static void checkDependsAndWires(FlowDefinition flow, List<FlowDiagnostic> out) {
    for (FlowNode node : flow.nodes().values()) {
      int d = 0;
      for (String dep : node.dependsOn()) {
        if (!flow.nodes().containsKey(dep)) {
          out.add(
              FlowDiagnostic.error(
                  "UNKNOWN_NODE_REF",
                  "nodes." + node.id() + ".dependsOn[" + d + "]",
                  "dependsOn 引用了不存在的 node: " + dep));
        }
        d++;
      }
      for (FlowPort port : node.inputs().values()) {
        String path = "nodes." + node.id() + ".inputs." + port.name();
        Optional<WireRef> wire = port.wire();
        if (port.fromOpt().isPresent() && wire.isEmpty()) {
          out.add(
              FlowDiagnostic.error(
                  "UNKNOWN_PORT_REF", path + ".from", "from 必须是 node.port 形式，实际=" + port.from()));
          continue;
        }
        if (wire.isEmpty()) {
          continue;
        }
        WireRef ref = wire.get();
        FlowNode producer = flow.nodes().get(ref.nodeId());
        if (producer == null) {
          out.add(
              FlowDiagnostic.error(
                  "UNKNOWN_NODE_REF", path + ".from", "from 引用了不存在的 node: " + ref.nodeId()));
          continue;
        }
        FlowPort outPort = producer.outputs().get(ref.portName());
        if (outPort == null) {
          out.add(
              FlowDiagnostic.error(
                  "UNKNOWN_PORT_REF",
                  path + ".from",
                  "from 引用了不存在的 port: " + ref.nodeId() + "." + ref.portName()));
          continue;
        }
        if (!FlowPortType.compatible(outPort.type(), port.type())) {
          out.add(
              FlowDiagnostic.error(
                  "TYPE_MISMATCH",
                  path,
                  "类型不兼容: "
                      + ref.nodeId()
                      + "."
                      + ref.portName()
                      + " ("
                      + outPort.type().name().toLowerCase(Locale.ROOT)
                      + ") -> "
                      + node.id()
                      + "."
                      + port.name()
                      + " ("
                      + port.type().name().toLowerCase(Locale.ROOT)
                      + ")"));
        }
      }
    }
  }

  private static void checkCycles(FlowDefinition flow, List<FlowDiagnostic> out) {
    Map<String, Set<String>> adj = new HashMap<>();
    for (String id : flow.nodes().keySet()) {
      adj.put(id, new HashSet<>());
    }
    for (FlowEdge edge : flow.edges()) {
      if (adj.containsKey(edge.from()) && adj.containsKey(edge.to())) {
        adj.get(edge.from()).add(edge.to());
      }
    }
    for (FlowNode node : flow.nodes().values()) {
      for (String dep : node.dependsOn()) {
        if (adj.containsKey(dep) && adj.containsKey(node.id())) {
          // dependsOn: dep must complete before node → edge dep → node
          adj.get(dep).add(node.id());
        }
      }
      for (FlowPort port : node.inputs().values()) {
        port.wire()
            .ifPresent(
                ref -> {
                  if (adj.containsKey(ref.nodeId()) && adj.containsKey(node.id())) {
                    adj.get(ref.nodeId()).add(node.id());
                  }
                });
      }
    }

    Set<String> visiting = new HashSet<>();
    Set<String> visited = new HashSet<>();
    List<String> stack = new ArrayList<>();
    for (String id : flow.nodes().keySet()) {
      if (!visited.contains(id)) {
        if (dfsCycle(id, adj, visiting, visited, stack, out)) {
          return;
        }
      }
    }
  }

  private static boolean dfsCycle(
      String node,
      Map<String, Set<String>> adj,
      Set<String> visiting,
      Set<String> visited,
      List<String> stack,
      List<FlowDiagnostic> out) {
    visiting.add(node);
    stack.add(node);
    for (String next : adj.getOrDefault(node, Set.of())) {
      if (visited.contains(next)) {
        continue;
      }
      if (visiting.contains(next)) {
        int idx = stack.indexOf(next);
        List<String> cycle = new ArrayList<>(stack.subList(idx, stack.size()));
        cycle.add(next);
        out.add(
            FlowDiagnostic.error(
                "CYCLE_DETECTED", "nodes", "检测到循环依赖: " + String.join(" -> ", cycle)));
        return true;
      }
      if (dfsCycle(next, adj, visiting, visited, stack, out)) {
        return true;
      }
    }
    stack.remove(stack.size() - 1);
    visiting.remove(node);
    visited.add(node);
    return false;
  }
}
