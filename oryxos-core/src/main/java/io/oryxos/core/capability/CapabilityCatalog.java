package io.oryxos.core.capability;

import java.util.List;

/** Live index of assemblable capabilities (tools / mcp / skills / knowledge / memory). */
public interface CapabilityCatalog {

  boolean contains(CapabilityRef ref);

  /** Snapshot sorted by {@link CapabilityRef#describe()}. */
  List<CapabilityRef> list();
}
