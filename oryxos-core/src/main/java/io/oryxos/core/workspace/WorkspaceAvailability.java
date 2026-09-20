package io.oryxos.core.workspace;

/** Nonblocking admission snapshot; implementations must not perform filesystem I/O. */
@FunctionalInterface
public interface WorkspaceAvailability {
  boolean available();
}
