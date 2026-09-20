package io.oryxos.core.flow;

import java.util.Objects;
import java.util.Optional;

/** Named typed port on a Flow node; optional {@code from} wire {@code node.port}. */
public record FlowPort(String name, FlowPortType type, String from, boolean required) {

  public FlowPort {
    name = Objects.requireNonNull(name, "name").strip();
    if (name.isEmpty()) {
      throw new IllegalArgumentException("port name 不能为空");
    }
    type = type == null ? FlowPortType.ANY : type;
    from = from == null || from.isBlank() ? null : from.strip();
  }

  public Optional<String> fromOpt() {
    return Optional.ofNullable(from);
  }

  /** Parse {@code node.port}; empty if wire is absent or malformed. */
  public Optional<WireRef> wire() {
    if (from == null) {
      return Optional.empty();
    }
    int dot = from.indexOf('.');
    if (dot <= 0 || dot >= from.length() - 1) {
      return Optional.empty();
    }
    return Optional.of(new WireRef(from.substring(0, dot), from.substring(dot + 1)));
  }

  public record WireRef(String nodeId, String portName) {}
}
