package io.oryxos.core.workspace;

import java.io.IOException;
import java.nio.file.Path;

/** Explicitly registered storage plugin; no implicit provider discovery or fallback. */
public interface WorkspaceStorageProvider {
  String id();

  WorkspaceStorage open(Path root, String expectedIdentity) throws IOException;
}
