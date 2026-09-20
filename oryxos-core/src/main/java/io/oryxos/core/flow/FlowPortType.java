package io.oryxos.core.flow;

import java.util.Locale;

/** Typed Flow port kinds (045 / #467). {@link #ANY} is a wildcard for static compatibility. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "IMPROPER_UNICODE",
    justification = "Flow DSL keywords are ASCII; Locale.ROOT fold is intentional.")
public enum FlowPortType {
  STRING,
  NUMBER,
  BOOLEAN,
  OBJECT,
  ANY;

  public static FlowPortType parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return ANY;
    }
    return switch (raw.strip().toLowerCase(Locale.ROOT)) {
      case "string", "str", "text" -> STRING;
      case "number", "int", "integer", "long", "double", "float" -> NUMBER;
      case "boolean", "bool" -> BOOLEAN;
      case "object", "map", "json" -> OBJECT;
      case "any", "*" -> ANY;
      default -> throw new IllegalArgumentException("未知 Flow port type: " + raw);
    };
  }

  /** True when a value of {@code source} may feed a port declared as {@code target}. */
  public static boolean compatible(FlowPortType source, FlowPortType target) {
    if (source == null || target == null) {
      return false;
    }
    if (source == ANY || target == ANY) {
      return true;
    }
    return source == target;
  }
}
