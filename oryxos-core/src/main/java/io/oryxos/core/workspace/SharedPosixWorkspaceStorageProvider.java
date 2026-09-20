package io.oryxos.core.workspace;

import java.io.IOException;
import java.nio.file.Path;

/** Existing shared POSIX mount; never initializes or falls back to local storage. */
public final class SharedPosixWorkspaceStorageProvider implements WorkspaceStorageProvider {
  @Override
  public String id() {
    return "shared-posix";
  }

  @Override
  public WorkspaceStorage open(Path root, String expectedIdentity) throws IOException {
    if (expectedIdentity == null || expectedIdentity.isBlank()) {
      throw new IllegalArgumentException("Shared workspace identity is required");
    }
    var storage =
        new NioWorkspaceStorage(id(), root.toAbsolutePath().normalize(), expectedIdentity);
    storage.checkHealth();
    WorkspaceCapabilityProbe.requireNativePosix(root);
    WorkspaceCapabilityProbe.verify(storage.root());
    return storage;
  }
}
