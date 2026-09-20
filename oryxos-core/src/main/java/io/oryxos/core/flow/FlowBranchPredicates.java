package io.oryxos.core.flow;

import java.util.Map;

/**
 * Minimal {@code when} predicate evaluator for Flow edges (046 / #468).
 *
 * <p>Supports {@code <port> == <literal>} and {@code <port> != <literal>} where {@code port} is a
 * bare name resolved against the latest waiting/current node outputs merged into context, or {@code
 * node.port} fully qualified keys in context.
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "IMPROPER_UNICODE",
    justification =
        "Flow DSL predicates are ASCII; case fold uses String.equalsIgnoreCase intentionally.")
public final class FlowBranchPredicates {

  private FlowBranchPredicates() {}

  public static boolean matches(
      String when, Map<String, Object> context, Map<String, Object> nodeOutputs) {
    if (when == null || when.isBlank()) {
      return true;
    }
    String expr = when.strip();
    String op = null;
    int idx = expr.indexOf("!=");
    if (idx > 0) {
      op = "!=";
    } else {
      idx = expr.indexOf("==");
      if (idx > 0) {
        op = "==";
      }
    }
    if (op == null) {
      return false;
    }
    String left = expr.substring(0, idx).strip();
    String right = expr.substring(idx + op.length()).strip();
    Object actual = resolve(left, context, nodeOutputs);
    String expected = unquote(right);
    boolean eq = objectsEqual(actual, expected);
    return "!=".equals(op) ? !eq : eq;
  }

  private static Object resolve(
      String left, Map<String, Object> context, Map<String, Object> nodeOutputs) {
    if (nodeOutputs != null && nodeOutputs.containsKey(left)) {
      return nodeOutputs.get(left);
    }
    if (context != null) {
      if (context.containsKey(left)) {
        return context.get(left);
      }
      // bare port: scan *.port
      String suffix = "." + left;
      for (Map.Entry<String, Object> e : context.entrySet()) {
        if (e.getKey().endsWith(suffix)) {
          return e.getValue();
        }
      }
    }
    return null;
  }

  private static String unquote(String raw) {
    if (raw.length() >= 2) {
      char a = raw.charAt(0);
      char b = raw.charAt(raw.length() - 1);
      if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
        return raw.substring(1, raw.length() - 1);
      }
    }
    return raw;
  }

  private static boolean objectsEqual(Object actual, String expected) {
    if (actual == null) {
      return expected == null || "null".equalsIgnoreCase(expected) || expected.isEmpty();
    }
    String a = String.valueOf(actual).strip();
    String e = expected == null ? "" : expected.strip();
    return a.equals(e) || a.equalsIgnoreCase(e);
  }

  /** Convenience: evaluate against context only. */
  public static boolean matches(String when, Map<String, Object> context) {
    return matches(when, context, Map.of());
  }
}
