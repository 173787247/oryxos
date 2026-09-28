package io.oryxos.core.capability;

import java.util.Locale;

/** Direction C 能力统一：装配层可引用的能力种类（Memory / Tool / MCP / Skill / 知识库）。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "IMPROPER_UNICODE",
    justification = "Capability kinds are ASCII keywords; Locale.ROOT fold is intentional.")
public enum CapabilityKind {
  TOOL,
  MCP,
  SKILL,
  KNOWLEDGE,
  MEMORY;

  public static CapabilityKind parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("capability kind 不能为空");
    }
    return switch (raw.strip().toLowerCase(Locale.ROOT)) {
      case "tool", "tools" -> TOOL;
      case "mcp", "mcp_server", "mcp-server" -> MCP;
      case "skill", "skills" -> SKILL;
      case "knowledge", "kb", "knowledge_base" -> KNOWLEDGE;
      case "memory", "mem" -> MEMORY;
      default -> throw new IllegalArgumentException("未知 capability kind: " + raw);
    };
  }
}
