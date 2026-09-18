package io.oryxos.core.workspace;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Startup validation for the built-ins, which support mounted native POSIX filesystems only. */
final class WorkspaceCapabilityProbe {
  private static final String REPLACEMENT_CONTENT = "new";

  private WorkspaceCapabilityProbe() {}

  static void requireNativePosix(Path root) throws IOException {
    if (root.getFileSystem() != FileSystems.getDefault()
        || !root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      throw new IOException("Built-in workspace providers require a native POSIX filesystem");
    }
    try {
      root.toFile();
    } catch (UnsupportedOperationException failure) {
      throw new IOException("Workspace provider has no native execution view", failure);
    }
  }

  static void verify(Path root) throws IOException {
    Path probe = root.resolve(".workspace-capability-" + UUID.randomUUID());
    Files.createDirectory(probe);
    Path source = probe.resolve("source");
    Path target = probe.resolve("target");
    Path link = probe.resolve("link");
    Throwable failure = null;
    try {
      Files.writeString(source, REPLACEMENT_CONTENT);
      Files.writeString(target, "old");
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      if (!REPLACEMENT_CONTENT.equals(Files.readString(target))) {
        throw new IOException("Workspace atomic replacement probe failed");
      }
      Files.createSymbolicLink(link, root.getFileSystem().getPath("target"));
      if (!Files.isSymbolicLink(link)
          || Files.readSymbolicLink(link).isAbsolute()
          || !REPLACEMENT_CONTENT.equals(Files.readString(link))) {
        throw new IOException("Workspace relative symbolic link probe failed");
      }
      Files.readAttributes(target, "posix:permissions", LinkOption.NOFOLLOW_LINKS);
    } catch (IOException | RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      IOException cleanupFailure = null;
      for (Path path : new Path[] {link, source, target, probe}) {
        try {
          Files.deleteIfExists(path);
        } catch (IOException e) {
          if (cleanupFailure == null) {
            cleanupFailure = e;
          } else {
            cleanupFailure.addSuppressed(e);
          }
        }
      }
      if (cleanupFailure != null) {
        if (failure != null) {
          failure.addSuppressed(cleanupFailure);
        } else {
          throw cleanupFailure;
        }
      }
    }
  }
}
