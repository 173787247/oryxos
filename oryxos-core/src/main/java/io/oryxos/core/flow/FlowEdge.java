package io.oryxos.core.flow;

import java.util.Objects;

/** Directed edge; optional {@code when} branch predicate (opaque string until #468). */
public record FlowEdge(String from, String to, String when) {

  public FlowEdge {
    from = Objects.requireNonNull(from, "from").strip();
    to = Objects.requireNonNull(to, "to").strip();
    if (from.isEmpty() || to.isEmpty()) {
      throw new IllegalArgumentException("edge from/to 不能为空");
    }
    when = when == null || when.isBlank() ? null : when.strip();
  }
}
