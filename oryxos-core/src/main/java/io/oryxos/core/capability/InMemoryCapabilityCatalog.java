package io.oryxos.core.capability;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Mutable catalog for Boot aggregation and tests. */
public final class InMemoryCapabilityCatalog implements CapabilityCatalog {

  private final Set<CapabilityRef> refs = ConcurrentHashMap.newKeySet();

  public InMemoryCapabilityCatalog() {}

  public InMemoryCapabilityCatalog(Iterable<CapabilityRef> initial) {
    if (initial != null) {
      for (CapabilityRef ref : initial) {
        add(ref);
      }
    }
  }

  public void add(CapabilityRef ref) {
    refs.add(Objects.requireNonNull(ref, "ref"));
  }

  public void addAll(Iterable<CapabilityRef> more) {
    if (more == null) {
      return;
    }
    for (CapabilityRef ref : more) {
      add(ref);
    }
  }

  @Override
  public boolean contains(CapabilityRef ref) {
    return ref != null && refs.contains(ref);
  }

  @Override
  public List<CapabilityRef> list() {
    return refs.stream().sorted(Comparator.comparing(CapabilityRef::describe)).toList();
  }

  /** Copy as LinkedHashSet for tests. */
  public Set<CapabilityRef> snapshot() {
    return new LinkedHashSet<>(refs);
  }
}
