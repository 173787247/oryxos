package io.oryxos.core.flow;

import java.util.List;

/** Optional permission surface (roles / agents) for a Flow definition. */
public record FlowPermissions(List<String> roles, List<String> agents) {

  public static final FlowPermissions EMPTY = new FlowPermissions(List.of(), List.of());

  public FlowPermissions {
    roles = roles == null ? List.of() : List.copyOf(roles);
    agents = agents == null ? List.of() : List.copyOf(agents);
  }
}
