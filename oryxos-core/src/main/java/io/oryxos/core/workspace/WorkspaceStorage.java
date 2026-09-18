package io.oryxos.core.workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/** Workspace paths carry their selected NIO provider through ordinary Files operations. */
public interface WorkspaceStorage extends AutoCloseable {
  String providerId();

  Path root();

  Set<WorkspaceCapability> capabilities();

  void checkHealth() throws IOException;

  /** Resolves a relative or absolute path, rejecting lexical escapes from the managed root. */
  Path resolve(String path);

  /** Explicit execution bridge; checks health before handing a path to native consumers. */
  Path nativePath(Path path) throws IOException;

  @Override
  default void close() throws IOException {}
}
