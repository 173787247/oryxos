package io.oryxos.core.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Default local filesystem, retaining first-start directory initialization. */
public final class LocalWorkspaceStorageProvider implements WorkspaceStorageProvider {
  @Override
  public String id() {
    return "local";
  }

  @Override
  public WorkspaceStorage open(Path root, String expectedIdentity) throws IOException {
    Path absoluteRoot = root.toAbsolutePath().normalize();
    WorkspaceCapabilityProbe.requireNativePosix(absoluteRoot);
    Files.createDirectories(absoluteRoot);
    WorkspaceCapabilityProbe.verify(absoluteRoot);
    return new NioWorkspaceStorage(id(), absoluteRoot, null);
  }
}
