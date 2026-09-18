package io.oryxos.core.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/** Both built-ins use the caller's filesystem delegate rather than reconstructing default paths. */
final class NioWorkspaceStorage implements WorkspaceStorage {
  private static final int MAX_IDENTITY_BYTES = 4096;
  private final String id;
  private final Path delegateRoot;
  private final String expectedIdentity;
  private final WorkspaceFileSystem fileSystem;

  NioWorkspaceStorage(String id, Path root, String expectedIdentity) {
    this.id = id;
    this.delegateRoot = root;
    this.expectedIdentity = expectedIdentity;
    this.fileSystem = new WorkspaceFileSystem(root, this::checkHealth);
  }

  @Override
  public String providerId() {
    return id;
  }

  @Override
  public Path root() {
    return fileSystem.wrap(delegateRoot);
  }

  @Override
  public Set<WorkspaceCapability> capabilities() {
    return expectedIdentity == null
        ? Set.of(
            WorkspaceCapability.STREAM_IO,
            WorkspaceCapability.ATOMIC_MOVE,
            WorkspaceCapability.SYMBOLIC_LINKS,
            WorkspaceCapability.NATIVE_EXECUTION_VIEW)
        : Set.of(WorkspaceCapability.values());
  }

  @Override
  public void checkHealth() throws IOException {
    try {
      checkHealthAvailable();
    } catch (NoSuchFileException unavailable) {
      throw new IOException("Shared workspace is unavailable", unavailable);
    }
  }

  private void checkHealthAvailable() throws IOException {
    // Native delegate only: using wrapped paths here would recurse into the health guard.
    if (!Files.readAttributes(delegateRoot, BasicFileAttributes.class).isDirectory()) {
      throw new IOException("Workspace root is not a directory");
    }
    if (expectedIdentity == null) {
      return;
    }
    Path marker = delegateRoot.resolve(".workspace-id");
    BasicFileAttributes attrs =
        Files.readAttributes(marker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attrs.isRegularFile() || attrs.isSymbolicLink()) {
      throw new IOException("Shared workspace identity must be a regular file");
    }
    // NOFOLLOW also applies to the open, closing the symlink swap gap after the attributes check.
    try (var channel =
        Files.newByteChannel(
            marker, Set.of(java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      if (channel.size() > MAX_IDENTITY_BYTES) {
        throw new IOException("Shared workspace identity is invalid");
      }
      var buffer = java.nio.ByteBuffer.allocate(MAX_IDENTITY_BYTES + 1);
      while (buffer.hasRemaining() && channel.read(buffer) != -1) {
        /* bounded marker read */
      }
      buffer.flip();
      String actual = java.nio.charset.StandardCharsets.UTF_8.decode(buffer).toString().strip();
      if (!expectedIdentity.equals(actual)) {
        throw new IOException("Shared workspace identity mismatch");
      }
    }
  }

  @Override
  public Path resolve(String path) {
    Path parsed = delegateRoot.getFileSystem().getPath(path);
    Path resolved = (parsed.isAbsolute() ? parsed : delegateRoot.resolve(parsed)).normalize();
    if (!resolved.startsWith(delegateRoot)) {
      throw new IllegalArgumentException("Path escapes workspace root");
    }
    return fileSystem.wrap(resolved);
  }

  @Override
  public Path nativePath(Path path) throws IOException {
    checkHealth();
    Path nativePath = fileSystem.checked(path, true);
    io.oryxos.core.fs.RealPathBoundary.requireWithin(delegateRoot, nativePath);
    return nativePath;
  }
}
