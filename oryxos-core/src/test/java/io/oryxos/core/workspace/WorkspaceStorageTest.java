package io.oryxos.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceStorageTest {
  @TempDir Path dir;

  @Test
  void operationsAndDerivedPathsRetainSelectedProvider() throws Exception {
    var storage = new LocalWorkspaceStorageProvider().open(dir.resolve("local"), null);
    Path root = storage.root();
    var provider = root.getFileSystem().provider();
    assertThat(provider).isNotSameAs(dir.getFileSystem().provider());
    Path source = storage.resolve("a");
    Files.writeString(source, "hello");
    assertThat(Files.readString(source)).isEqualTo("hello");
    assertThat(source.toRealPath().getFileSystem().provider()).isSameAs(provider);
    assertThat(source.getParent()).isEqualTo(root);
    assertThat(source.startsWith(dir)).isTrue();
    assertThat(root.resolve(dir.resolve("local/a"))).isEqualTo(source);
    assertThat(source.toRealPath().startsWith(root.toRealPath())).isTrue();
    Files.copy(source, root.resolve("copy"));
    Files.move(source, root.resolve("moved"), StandardCopyOption.ATOMIC_MOVE);
    Files.createSymbolicLink(root.resolve("link"), Path.of("moved"));
    assertThat(Files.readSymbolicLink(root.resolve("link")).toString()).isEqualTo("moved");
    assertThat(Files.readString(root.resolve("link"))).isEqualTo("hello");
    assertThat(
            Files.readAttributes(
                root.resolve("link"), "basic:isSymbolicLink", LinkOption.NOFOLLOW_LINKS))
        .containsEntry("isSymbolicLink", true);
    try (var children = Files.list(root)) {
      assertThat(children.toList())
          .allSatisfy(path -> assertThat(path.getFileSystem().provider()).isSameAs(provider));
    }
    assertThat(storage.nativePath(root.resolve("moved"))).isEqualTo(dir.resolve("local/moved"));
    assertThrows(UnsupportedOperationException.class, root::toFile);
    assertThrows(IllegalArgumentException.class, () -> storage.resolve("../outside"));
    assertThrows(IllegalArgumentException.class, () -> storage.resolve(dir.toString()));
  }

  @Test
  void sharedRejectsMissingWrongAndLinkedIdentityWithoutCreatingRoot() throws Exception {
    var provider = new SharedPosixWorkspaceStorageProvider();
    Path missing = dir.resolve("missing");
    assertThrows(IOException.class, () -> provider.open(missing, "expected"));
    assertThat(Files.exists(missing)).isFalse();
    assertThrows(IOException.class, () -> provider.open(dir, "expected"));
    Files.writeString(dir.resolve(".workspace-id"), "wrong");
    assertThrows(IOException.class, () -> provider.open(dir, "expected"));
    Files.delete(dir.resolve(".workspace-id"));
    Files.writeString(dir.resolve("identity"), "expected");
    Files.createSymbolicLink(dir.resolve(".workspace-id"), Path.of("identity"));
    assertThrows(IOException.class, () -> provider.open(dir, "expected"));
  }

  @Test
  void everyIoChecksIdentityIncludingOpenChannelsAndAttributeViews() throws Exception {
    Files.writeString(dir.resolve(".workspace-id"), "expected\n");
    var storage = new SharedPosixWorkspaceStorageProvider().open(dir, "expected");
    Path file = storage.resolve("data");
    Files.writeString(file, "old");
    var attributes = Files.getFileAttributeView(file, BasicFileAttributeView.class);
    try (var channel = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
      Files.writeString(dir.resolve(".workspace-id"), "wrong");
      assertThrows(
          IOException.class, () -> channel.write(java.nio.ByteBuffer.wrap(new byte[] {1})));
      assertThrows(IOException.class, attributes::readAttributes);
      assertThrows(IOException.class, () -> Files.readAttributes(file, "basic:size"));
      assertThrows(IOException.class, () -> Files.newDirectoryStream(storage.root()));
      assertThrows(IOException.class, () -> Files.delete(file));
      assertThrows(IOException.class, () -> storage.nativePath(file));
      assertThrows(IOException.class, () -> file.getFileSystem().provider().checkAccess(file));
      assertThrows(IOException.class, () -> Files.writeString(file, "new"));
    }
    assertThat(Files.readString(dir.resolve("data"))).isEqualTo("old");
  }

  @Test
  void disappearingSharedRootIsNeverRecreated() throws Exception {
    Path root = Files.createDirectory(dir.resolve("mounted"));
    Files.writeString(root.resolve(".workspace-id"), "expected");
    var storage = new SharedPosixWorkspaceStorageProvider().open(root, "expected");
    Files.move(root, dir.resolve("unmounted"));
    assertThrows(IOException.class, () -> Files.createDirectories(storage.resolve("nested")));
    assertThrows(IOException.class, () -> Files.writeString(storage.resolve("file"), "no"));
    assertThat(Files.exists(root)).isFalse();
  }

  @Test
  void selectedPluginDispatchesActualProviderOperations() throws Exception {
    RecordingWorkspaceProvider recorder =
        new RecordingWorkspaceProvider(dir.getFileSystem().provider());
    var fileSystem = new WorkspaceFileSystem(dir, () -> {}, recorder);
    WorkspaceStorage pluginStorage =
        new WorkspaceStorage() {
          public String providerId() {
            return "recording";
          }

          public Path root() {
            return fileSystem.wrap(dir);
          }

          public Set<WorkspaceCapability> capabilities() {
            return Set.of(WorkspaceCapability.values());
          }

          public void checkHealth() {}

          public Path resolve(String path) {
            return root().resolve(path);
          }

          public Path nativePath(Path path) {
            recorder.record("nativePath");
            return fileSystem.unwrap(path);
          }
        };
    var registry =
        new WorkspaceStorageRegistry(
            List.of(
                new WorkspaceStorageProvider() {
                  public String id() {
                    return "recording";
                  }

                  public WorkspaceStorage open(Path root, String identity) {
                    return pluginStorage;
                  }
                }));
    var storage = registry.open("recording", dir, null);
    Path file = storage.resolve("delegated");
    Files.writeString(file, "routed");
    assertThat(Files.readString(file)).isEqualTo("routed");
    Files.readAttributes(file, "basic:size");
    Files.copy(file, storage.resolve("copy"));
    Files.move(file, storage.resolve("moved"), StandardCopyOption.ATOMIC_MOVE);
    Files.createSymbolicLink(storage.resolve("link"), Path.of("moved"));
    assertThat(Files.readSymbolicLink(storage.resolve("link")).isAbsolute()).isFalse();
    assertThat(storage.resolve("link").toRealPath().getFileSystem()).isSameAs(fileSystem);
    try (var children = Files.list(storage.root())) {
      assertThat(children.toList())
          .allSatisfy(path -> assertThat(path.getFileSystem()).isSameAs(fileSystem));
    }
    Files.delete(storage.resolve("copy"));
    assertThat(storage.nativePath(storage.resolve("moved"))).isEqualTo(dir.resolve("moved"));
    assertThat(recorder.operations)
        .contains(
            "newByteChannel",
            "readAttributes",
            "copy",
            "move",
            "createSymbolicLink",
            "readSymbolicLink",
            "newDirectoryStream",
            "delete",
            "nativePath");
  }

  @Test
  void derivedPathsAndExternalSymlinksCannotEscapeIoBoundary() throws Exception {
    Path managed = Files.createDirectory(dir.resolve("managed"));
    Path external = Files.writeString(dir.resolve("outside"), "safe");
    var storage = new LocalWorkspaceStorageProvider().open(managed, null);
    for (Path escaped :
        List.of(
            storage.root().resolve("../outside"),
            storage.root().resolveSibling("outside"),
            storage.root().getParent().resolve("outside"),
            storage.root().getRoot().resolve(external),
            storage.root().resolve(external))) {
      assertThrows(IOException.class, () -> Files.readString(escaped));
      assertThrows(IOException.class, () -> Files.writeString(escaped, "bad"));
      assertThrows(IOException.class, () -> Files.delete(escaped));
      assertThrows(IOException.class, escaped::toRealPath);
    }
    Files.createSymbolicLink(managed.resolve("escape"), external);
    Path escape = storage.resolve("escape");
    assertThrows(IOException.class, () -> Files.readString(escape));
    assertThrows(IOException.class, () -> Files.writeString(escape, "bad"));
    assertThrows(IOException.class, () -> storage.nativePath(escape));
    assertThat(Files.isSymbolicLink(escape)).isTrue();
    Files.delete(escape);
    Path dangling = storage.resolve("dangling");
    Files.createSymbolicLink(dangling, Path.of("missing/nested"));
    assertThat(Files.isSymbolicLink(dangling)).isTrue();
    assertThat(Files.readSymbolicLink(dangling).toString()).isEqualTo("missing/nested");
    Files.delete(dangling);
    Files.createDirectories(storage.resolve("new/nested"));
    Files.writeString(storage.resolve("new/nested/file"), "inside");
    assertThat(Files.readString(external)).isEqualTo("safe");
  }

  @Test
  void builtinsRejectNonNativeFilesystemsAndCleanCapabilityProbe() throws Exception {
    var storage = new LocalWorkspaceStorageProvider().open(dir, null);
    try (var entries = Files.list(dir)) {
      assertThat(entries)
          .noneMatch(path -> path.getFileName().toString().startsWith(".workspace-capability-"));
    }
    // A wrapped filesystem is deliberately not an operating-system native execution view.
    assertThrows(
        IOException.class, () -> new LocalWorkspaceStorageProvider().open(storage.root(), null));
    Files.writeString(dir.resolve(".workspace-id"), "wrong");
    assertThrows(
        IOException.class, () -> new SharedPosixWorkspaceStorageProvider().open(dir, "expected"));
    try (var entries = Files.list(dir)) {
      assertThat(entries.map(path -> path.getFileName().toString()).toList())
          .containsExactly(".workspace-id");
    }
  }

  @Test
  void failedCapabilityProbesRemoveAllTemporaryEntries() throws Exception {
    for (String operation : List.of("move", "createSymbolicLink")) {
      var recorder = new RecordingWorkspaceProvider(dir.getFileSystem().provider());
      recorder.failOperation = operation;
      var fileSystem = new WorkspaceFileSystem(dir, () -> {}, recorder);
      assertThrows(
          UnsupportedOperationException.class,
          () -> WorkspaceCapabilityProbe.verify(fileSystem.wrap(dir)));
      try (var entries = Files.list(dir)) {
        assertThat(entries).isEmpty();
      }
    }
  }

  @Test
  void realPathsRemainUsableWhenRootHasASymlinkAlias() throws Exception {
    Path root = Files.createDirectory(dir.resolve("actual"));
    Path alias = Files.createSymbolicLink(dir.resolve("alias"), Path.of("actual"));
    var storage = new LocalWorkspaceStorageProvider().open(alias, null);
    Files.writeString(storage.resolve("file"), "value");
    Path real = storage.resolve("file").toRealPath();
    assertThat(Files.readString(real)).isEqualTo("value");
    assertThat(storage.nativePath(real)).isEqualTo(root.resolve("file").toRealPath());
  }

  @Test
  void hardLinksRejectExternalNativeSourcesAndAllowInternalSources() throws Exception {
    Path managed = Files.createDirectory(dir.resolve("managed"));
    Path external = Files.writeString(dir.resolve("external"), "outside");
    var storage = new LocalWorkspaceStorageProvider().open(managed, null);
    Path denied = storage.resolve("denied");
    assertThrows(IOException.class, () -> Files.createLink(denied, external));
    assertThat(Files.exists(managed.resolve("denied"))).isFalse();
    assertThat(Files.readString(external)).isEqualTo("outside");
    Path internal = storage.resolve("internal");
    Files.writeString(internal, "inside");
    Path accepted = storage.resolve("accepted");
    Files.createLink(accepted, internal);
    assertThat(Files.isSameFile(internal, accepted)).isTrue();
    Files.writeString(accepted, "updated");
    assertThat(Files.readString(internal)).isEqualTo("updated");
    Files.createSymbolicLink(managed.resolve("external-alias"), external);
    assertThrows(
        IOException.class,
        () -> Files.createLink(storage.resolve("denied-alias"), storage.resolve("external-alias")));
    assertThat(Files.exists(managed.resolve("denied-alias"))).isFalse();
  }

  @Test
  void atomicMoveAcrossWorkspaceInstancesDoesNotSilentlyDegrade() throws Exception {
    var first = new LocalWorkspaceStorageProvider().open(dir.resolve("first"), null);
    var second = new LocalWorkspaceStorageProvider().open(dir.resolve("second"), null);
    Files.writeString(first.resolve("file"), "original");
    assertThrows(
        java.nio.file.AtomicMoveNotSupportedException.class,
        () ->
            Files.move(
                first.resolve("file"), second.resolve("file"), StandardCopyOption.ATOMIC_MOVE));
    assertThat(Files.readString(first.resolve("file"))).isEqualTo("original");
    assertThat(Files.exists(second.resolve("file"))).isFalse();
  }

  @Test
  void localWatchServiceDeliversEventsUsableWithWrappedRoot() throws Exception {
    var storage = new LocalWorkspaceStorageProvider().open(dir, null);
    try (var watcher = storage.root().getFileSystem().newWatchService()) {
      storage.root().register(watcher, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
      Files.writeString(storage.resolve("watched"), "event");
      var key = watcher.poll(5, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(key).isNotNull();
      assertThat(key.pollEvents())
          .anySatisfy(
              event -> {
                Path observed = storage.root().resolve((Path) event.context());
                assertThat(observed).isEqualTo(storage.resolve("watched"));
                assertThat(observed.getFileSystem()).isSameAs(storage.root().getFileSystem());
              });
    }
  }

  @Test
  void registryRejectsDuplicateUnknownAndIncompletePlugins() throws Exception {
    var local = new LocalWorkspaceStorageProvider();
    assertThrows(
        IllegalArgumentException.class, () -> new WorkspaceStorageRegistry(List.of(local, local)));
    var registry = new WorkspaceStorageRegistry(List.of(local));
    assertThrows(IllegalArgumentException.class, () -> registry.open("unknown", dir, null));
    assertThat(registry.open("local", dir, null).providerId()).isEqualTo("local");
    WorkspaceStorageProvider incomplete =
        new WorkspaceStorageProvider() {
          public String id() {
            return "incomplete";
          }

          public WorkspaceStorage open(Path root, String identity) {
            return new WorkspaceStorage() {
              public String providerId() {
                return "incomplete";
              }

              public Path root() {
                return root;
              }

              public Set<WorkspaceCapability> capabilities() {
                return Set.of(WorkspaceCapability.STREAM_IO);
              }

              public void checkHealth() {}

              public Path resolve(String value) {
                return root.resolve(value);
              }

              public Path nativePath(Path path) {
                return path;
              }
            };
          }
        };
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorkspaceStorageRegistry(List.of(incomplete)).open("incomplete", dir, null));
  }
}
