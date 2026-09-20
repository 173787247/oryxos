package io.oryxos.core.flow;

import java.util.Locale;

/**
 * Declared Flow node kinds (045 / #467). {@code HUMAN}/{@code APPROVAL} are schema-forward for
 * #469; this cut only parses and validates them.
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "IMPROPER_UNICODE",
    justification = "Flow DSL keywords are ASCII; Locale.ROOT fold is intentional.")
public enum FlowNodeType {
  AGENT,
  TOOL,
  NOTIFY,
  HUMAN,
  APPROVAL;

  public static FlowNodeType parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("node.type 不能为空");
    }
    return switch (raw.strip().toLowerCase(Locale.ROOT)) {
      case "agent" -> AGENT;
      case "tool" -> TOOL;
      case "notify", "notification" -> NOTIFY;
      case "human", "hitl" -> HUMAN;
      case "approval" -> APPROVAL;
      default -> throw new IllegalArgumentException("未知 Flow node type: " + raw);
    };
  }
}
