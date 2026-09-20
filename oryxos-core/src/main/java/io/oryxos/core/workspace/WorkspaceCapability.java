package io.oryxos.core.workspace;

/** Filesystem guarantees required by workspace consumers. */
public enum WorkspaceCapability {
  STREAM_IO,
  ATOMIC_MOVE,
  SYMBOLIC_LINKS,
  NATIVE_EXECUTION_VIEW,
  SHARED
}
