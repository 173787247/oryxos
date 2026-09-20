package io.oryxos.core.workspace.versioned;

import io.oryxos.core.cluster.ClusterProperties;
import io.oryxos.core.cluster.WorkspaceVersionNotifier;
import io.oryxos.core.fs.RealPathBoundary;
import io.oryxos.core.io.AtomicFiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Shared versioned source for Agent / Skill / Knowledge (#473): immutable snapshots on the
 * workspace volume, active pointer in {@link VersionedAssetPointerStore}, atomic activate /
 * rollback of the live tree.
 */
public final class VersionedAssetSource {

  public static final String VERSIONS_DIR = ".asset-versions";

  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]+");

  private final Path root;
  private final VersionedAssetPointerStore pointers;
  private final ClusterProperties cluster;
  private final WorkspaceVersionNotifier notifier;

  public VersionedAssetSource(
      Path workspaceRoot,
      VersionedAssetPointerStore pointers,
      ClusterProperties cluster,
      WorkspaceVersionNotifier notifier) {
    this.root = workspaceRoot.toAbsolutePath().normalize();
    this.pointers = pointers;
    this.cluster = cluster;
    this.notifier = notifier == null ? WorkspaceVersionNotifier.NOOP : notifier;
  }

  public boolean isEnabled() {
    return cluster != null && cluster.isVersionedAssetSourceEnabled();
  }

  public OptionalLong activeVersion(VersionedAssetKind kind, String assetId) {
    requireEnabled();
    return pointers.activeVersion(kind, safe(assetId));
  }

  public List<AssetVersion> list(VersionedAssetKind kind, String assetId) {
    requireEnabled();
    String id = safe(assetId);
    return pointers.listVersions(kind, id).stream()
        .map(v -> new AssetVersion(kind, id, v, ""))
        .toList();
  }

  /** Snapshot the live tree as the next version; first publish also activates. */
  public AssetVersion publish(VersionedAssetKind kind, String assetId, String actor) {
    requireEnabled();
    String id = safe(assetId);
    Path live = liveDir(kind, id);
    if (!Files.isDirectory(live)) {
      throw new IllegalArgumentException("live asset missing: " + kind.directory() + "/" + id);
    }
    long version = pointers.nextVersion(kind, id);
    Path snapshot = snapshotDir(kind, id, version);
    try {
      copyTree(live, snapshot);
      String hash = fingerprint(snapshot);
      AtomicFiles.writeString(snapshot.resolve(".content-hash"), hash + "\n");
      pointers.recordVersion(kind, id, version, hash, actor == null ? "anonymous" : actor);
      if (pointers.activeVersion(kind, id).isEmpty()) {
        pointers.activate(kind, id, version, actor == null ? "anonymous" : actor);
      }
      return new AssetVersion(kind, id, version, hash);
    } catch (IOException e) {
      throw new UncheckedIOException("publish failed: " + kind.directory() + "/" + id, e);
    }
  }

  /** Materialize snapshot → live (atomic rename), update shared pointer, bump notify bus. */
  public AssetVersion activate(
      VersionedAssetKind kind, String assetId, long version, String actor) {
    requireEnabled();
    String id = safe(assetId);
    if (version <= 0 || !pointers.hasVersion(kind, id, version)) {
      throw new IllegalArgumentException("unknown version: " + version);
    }
    Path snapshot = snapshotDir(kind, id, version);
    if (!Files.isDirectory(snapshot)) {
      throw new IllegalStateException("snapshot missing on volume: " + snapshot);
    }
    try {
      materialize(kind, id, snapshot);
      if (!pointers.activate(kind, id, version, actor == null ? "anonymous" : actor)) {
        throw new IllegalStateException("activate pointer failed for version " + version);
      }
      notifier.bump(kind.directory());
      String hash = readHash(snapshot);
      return new AssetVersion(kind, id, version, hash);
    } catch (IOException e) {
      throw new UncheckedIOException("activate failed: " + kind.directory() + "/" + id, e);
    }
  }

  /** Switch to previous active version (one-step rollback). */
  public AssetVersion rollback(VersionedAssetKind kind, String assetId, String actor) {
    requireEnabled();
    String id = safe(assetId);
    OptionalLong previous = pointers.previousVersion(kind, id);
    if (previous.isEmpty()) {
      throw new IllegalStateException("nothing to rollback: " + kind.directory() + "/" + id);
    }
    return activate(kind, id, previous.getAsLong(), actor);
  }

  private void materialize(VersionedAssetKind kind, String id, Path snapshot) throws IOException {
    Path live = liveDir(kind, id);
    Path parent = live.getParent();
    if (parent == null) {
      throw new IOException("live path has no parent: " + live);
    }
    Files.createDirectories(parent);
    Path staging = parent.resolve("." + id + ".staging-" + UUID.randomUUID());
    Path replaced = parent.resolve("." + id + ".replaced-" + UUID.randomUUID());
    try {
      copyTree(snapshot, staging);
      Files.deleteIfExists(staging.resolve(".content-hash"));
      if (Files.exists(live)) {
        Files.move(live, replaced, StandardCopyOption.ATOMIC_MOVE);
      }
      Files.move(staging, live, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      deleteRecursivelyQuietly(staging);
      if (Files.exists(replaced) && !Files.exists(live)) {
        try {
          Files.move(replaced, live, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
          // leave replaced for offline recovery
        }
      }
      throw e;
    }
    deleteRecursivelyQuietly(replaced);
  }

  private Path liveDir(VersionedAssetKind kind, String id) {
    return RealPathBoundary.requireWithin(root, root.resolve(kind.directory()).resolve(id));
  }

  private Path snapshotDir(VersionedAssetKind kind, String id, long version) {
    Path base =
        root.resolve(VERSIONS_DIR).resolve(kind.directory()).resolve(id).resolve("v" + version);
    return RealPathBoundary.requireWithin(root, base);
  }

  private void requireEnabled() {
    if (!isEnabled()) {
      throw new IllegalStateException("versioned asset source disabled");
    }
  }

  private static String safe(String assetId) {
    if (assetId == null || !SAFE_ID.matcher(assetId).matches()) {
      throw new IllegalArgumentException("invalid asset id: " + assetId);
    }
    return assetId;
  }

  private static void copyTree(Path source, Path target) throws IOException {
    Files.createDirectories(target);
    try (Stream<Path> walk = Files.walk(source)) {
      for (Path path : walk.toList()) {
        if (path.equals(source)) {
          continue;
        }
        Path relative = source.relativize(path);
        if (relative.startsWith(VERSIONS_DIR) || relative.toString().startsWith(".")) {
          // skip nested version dirs and staging markers inside live trees
          if (relative.getNameCount() > 0
              && relative.getName(0).toString().startsWith(".")
              && !relative.getName(0).toString().equals(".content-hash")) {
            continue;
          }
        }
        Path dest = RealPathBoundary.requireWithin(target, target.resolve(relative));
        if (Files.isDirectory(path)) {
          Files.createDirectories(dest);
        } else if (Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
          Files.createDirectories(dest.getParent());
          Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private static String fingerprint(Path snapshot) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    try (Stream<Path> walk = Files.walk(snapshot)) {
      List<Path> files =
          walk.filter(Files::isRegularFile)
              .filter(p -> !Files.isSymbolicLink(p))
              .sorted(Comparator.comparing(p -> snapshot.relativize(p).toString()))
              .toList();
      for (Path file : files) {
        String rel = snapshot.relativize(file).toString().replace('\\', '/');
        digest.update(rel.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
        try (InputStream in = Files.newInputStream(file)) {
          in.transferTo(
              new java.io.OutputStream() {
                @Override
                public void write(int b) {
                  digest.update((byte) b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                  digest.update(b, off, len);
                }
              });
        }
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String readHash(Path snapshot) throws IOException {
    Path marker = snapshot.resolve(".content-hash");
    if (Files.isRegularFile(marker)) {
      return Files.readString(marker).strip();
    }
    return fingerprint(snapshot);
  }

  private static void deleteRecursivelyQuietly(Path path) {
    if (path == null || !Files.exists(path)) {
      return;
    }
    try {
      Files.walkFileTree(
          path,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.deleteIfExists(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
              Files.deleteIfExists(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException ignored) {
      // best-effort cleanup
    }
  }
}
