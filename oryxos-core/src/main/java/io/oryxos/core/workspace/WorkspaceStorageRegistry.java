package io.oryxos.core.workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fail-closed plugin selection and capability validation. */
public final class WorkspaceStorageRegistry {
  private static final Set<WorkspaceCapability> REQUIRED =
      Set.of(
          WorkspaceCapability.STREAM_IO, WorkspaceCapability.ATOMIC_MOVE,
          WorkspaceCapability.SYMBOLIC_LINKS, WorkspaceCapability.NATIVE_EXECUTION_VIEW);
  private final Map<String, WorkspaceStorageProvider> providers;

  public WorkspaceStorageRegistry(List<WorkspaceStorageProvider> providers) {
    Map<String, WorkspaceStorageProvider> entries = new HashMap<>();
    for (WorkspaceStorageProvider provider : providers) {
      String id = Objects.requireNonNull(provider.id(), "provider id");
      if (id.isBlank() || entries.putIfAbsent(id, provider) != null) {
        throw new IllegalArgumentException("Invalid or duplicate workspace provider: " + id);
      }
    }
    this.providers = Map.copyOf(entries);
  }

  public WorkspaceStorage open(String id, Path root, String expectedIdentity) throws IOException {
    WorkspaceStorageProvider provider = providers.get(id);
    if (provider == null) {
      throw new IllegalArgumentException("Unknown workspace provider: " + id);
    }
    WorkspaceStorage storage = provider.open(root, expectedIdentity);
    try {
      if (!id.equals(storage.providerId()) || !storage.capabilities().containsAll(REQUIRED)) {
        throw new IllegalArgumentException(
            "Workspace provider identity or capabilities invalid: " + id);
      }
      storage.checkHealth();
      return storage;
    } catch (IOException | RuntimeException failure) {
      try {
        storage.close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }
}
