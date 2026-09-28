package io.oryxos.core.capability;

import java.util.Locale;
import java.util.Objects;

/**
 * 统一能力引用：{@code kind:name}（如 {@code skill:summarizer}、{@code tool:web_search}）。
 *
 * <p>与 RBAC {@code ResourceRef} 互补：这里服务装配 / Flow 声明，不参与授权决策。
 */
public record CapabilityRef(CapabilityKind kind, String name) {

  public CapabilityRef {
    kind = Objects.requireNonNull(kind, "kind");
    name = Objects.requireNonNull(name, "name").strip();
    if (name.isEmpty()) {
      throw new IllegalArgumentException("capability name 不能为空");
    }
  }

  public static CapabilityRef of(CapabilityKind kind, String name) {
    return new CapabilityRef(kind, name);
  }

  public static CapabilityRef tool(String name) {
    return new CapabilityRef(CapabilityKind.TOOL, name);
  }

  public static CapabilityRef mcp(String name) {
    return new CapabilityRef(CapabilityKind.MCP, name);
  }

  public static CapabilityRef skill(String name) {
    return new CapabilityRef(CapabilityKind.SKILL, name);
  }

  public static CapabilityRef knowledge(String name) {
    return new CapabilityRef(CapabilityKind.KNOWLEDGE, name);
  }

  public static CapabilityRef memory(String name) {
    return new CapabilityRef(CapabilityKind.MEMORY, name);
  }

  /** Parse {@code kind:name} or {@code kind/name}. Rejects blank / missing separator. */
  public static CapabilityRef parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("capability ref 不能为空");
    }
    String s = raw.strip();
    int sep = s.indexOf(':');
    if (sep < 0) {
      sep = s.indexOf('/');
    }
    if (sep <= 0 || sep >= s.length() - 1) {
      throw new IllegalArgumentException("capability ref 须为 kind:name，实际=" + raw);
    }
    return new CapabilityRef(
        CapabilityKind.parse(s.substring(0, sep)), s.substring(sep + 1).strip());
  }

  /** Stable catalog key. */
  public String describe() {
    return kind.name().toLowerCase(Locale.ROOT) + ":" + name;
  }
}
