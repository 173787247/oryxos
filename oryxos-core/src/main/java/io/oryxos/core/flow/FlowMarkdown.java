package io.oryxos.core.flow;

import io.oryxos.core.agent.AgentMarkdown;
import io.oryxos.core.agent.AgentMarkdown.Parsed;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parse a {@code .flow.md} document into {@link FlowDefinition} (045 / #467).
 *
 * <p>Frontmatter holds the machine graph; Markdown body is the human review narrative.
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "IMPROPER_UNICODE",
    justification = "Flow DSL keywords are ASCII; Locale.ROOT fold is intentional.")
public final class FlowMarkdown {

  private static final String KIND_FLOW = "Flow";

  private FlowMarkdown() {}

  public static FlowDefinition parse(String markdown) {
    Objects.requireNonNull(markdown, "markdown");
    Parsed parsed = AgentMarkdown.split(markdown);
    Map<String, Object> fm = parsed.frontmatter();
    if (fm.isEmpty()) {
      throw new IllegalArgumentException("Flow 文档缺少 YAML frontmatter");
    }

    String apiVersion = str(fm.get("apiVersion"));
    String kind = str(fm.get("kind"));
    if (kind.isEmpty()) {
      kind = KIND_FLOW;
    }
    if (!KIND_FLOW.equalsIgnoreCase(kind)) {
      throw new IllegalArgumentException("不支持的 kind: " + kind + "（期望 Flow）");
    }

    String id = firstNonBlank(str(fm.get("id")), str(fm.get("name")));
    String version = str(fm.get("version"));
    String entry = str(fm.get("entry"));

    Map<String, FlowNode> nodes = parseNodes(fm.get("nodes"));
    List<FlowEdge> edges = parseEdges(fm.get("edges"));
    FlowBudget budget = parseBudget(fm.get("budget"));
    FlowPermissions permissions = parsePermissions(fm.get("permissions"));

    return new FlowDefinition(
        apiVersion, id, version, entry, nodes, edges, budget, permissions, parsed.body());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, FlowNode> parseNodes(Object raw) {
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("nodes 必须是映射");
    }
    Map<String, FlowNode> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : map.entrySet()) {
      String nodeId = String.valueOf(e.getKey()).strip();
      if (!(e.getValue() instanceof Map<?, ?> nodeMap)) {
        throw new IllegalArgumentException("node '" + nodeId + "' 必须是映射");
      }
      out.put(nodeId, parseNode(nodeId, (Map<String, Object>) nodeMap));
    }
    return out;
  }

  private static FlowNode parseNode(String id, Map<String, Object> map) {
    FlowNodeType type = FlowNodeType.parse(str(map.get("type")));
    String ref = firstNonBlank(str(map.get("ref")), str(map.get("agent")), str(map.get("tool")));
    Map<String, FlowPort> inputs = parsePorts(id, "inputs", map.get("inputs"));
    Map<String, FlowPort> outputs = parsePorts(id, "outputs", map.get("outputs"));
    List<String> dependsOn = stringList(map.get("dependsOn"));
    if (dependsOn.isEmpty()) {
      dependsOn = stringList(map.get("depends"));
    }
    Integer timeoutSeconds =
        intOrNull(
            map.containsKey("timeoutSeconds") ? map.get("timeoutSeconds") : map.get("timeout"));
    String compensate =
        firstNonBlank(str(map.get("compensate")), str(map.get("onFailureCompensate")));
    return new FlowNode(id, type, ref, inputs, outputs, dependsOn, timeoutSeconds, compensate);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, FlowPort> parsePorts(String nodeId, String field, Object raw) {
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(nodeId + "." + field + " 必须是映射");
    }
    Map<String, FlowPort> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : map.entrySet()) {
      String portName = String.valueOf(e.getKey()).strip();
      Object val = e.getValue();
      if (val instanceof String s) {
        out.put(portName, new FlowPort(portName, FlowPortType.parse(s), null, false));
        continue;
      }
      if (!(val instanceof Map<?, ?> portMap)) {
        throw new IllegalArgumentException(nodeId + "." + field + "." + portName + " 必须是映射或类型名");
      }
      Map<String, Object> pm = (Map<String, Object>) portMap;
      FlowPortType type = FlowPortType.parse(str(pm.get("type")));
      String from = str(pm.get("from"));
      boolean required = bool(pm.get("required"), false);
      out.put(portName, new FlowPort(portName, type, from.isEmpty() ? null : from, required));
    }
    return out;
  }

  private static List<FlowEdge> parseEdges(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException("edges 必须是列表");
    }
    List<FlowEdge> out = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) {
        throw new IllegalArgumentException("edge 项必须是映射");
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> m = (Map<String, Object>) map;
      out.add(new FlowEdge(str(m.get("from")), str(m.get("to")), str(m.get("when"))));
    }
    return out;
  }

  private static FlowBudget parseBudget(Object raw) {
    if (!(raw instanceof Map<?, ?> map)) {
      return FlowBudget.EMPTY;
    }
    return new FlowBudget(
        intOrNull(map.get("maxDurationSeconds")),
        intOrNull(map.get("maxToolCalls")),
        intOrNull(map.get("maxTokens")));
  }

  private static FlowPermissions parsePermissions(Object raw) {
    if (!(raw instanceof Map<?, ?> map)) {
      return FlowPermissions.EMPTY;
    }
    return new FlowPermissions(stringList(map.get("roles")), stringList(map.get("agents")));
  }

  private static List<String> stringList(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (Object item : list) {
      if (item == null) {
        continue;
      }
      String s = String.valueOf(item).strip();
      if (!s.isEmpty()) {
        out.add(s);
      }
    }
    return out;
  }

  private static Integer intOrNull(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof Number n) {
      return n.intValue();
    }
    String s = String.valueOf(raw).strip();
    if (s.isEmpty()) {
      return null;
    }
    return Integer.parseInt(s);
  }

  private static boolean bool(Object raw, boolean defaultValue) {
    if (raw == null) {
      return defaultValue;
    }
    if (raw instanceof Boolean b) {
      return b;
    }
    return Boolean.parseBoolean(String.valueOf(raw));
  }

  private static String str(Object raw) {
    return raw == null ? "" : String.valueOf(raw).strip();
  }

  private static String firstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v.strip();
      }
    }
    return "";
  }
}
