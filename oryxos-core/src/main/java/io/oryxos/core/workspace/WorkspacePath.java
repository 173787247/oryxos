package io.oryxos.core.workspace;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Iterator;

/**
 * Pure path operations preserve the storage instance; IO always returns to its guarded provider.
 */
final class WorkspacePath implements Path {
  private final WorkspaceFileSystem fileSystem;
  private final Path delegate;

  WorkspacePath(WorkspaceFileSystem fileSystem, Path delegate) {
    this.fileSystem = fileSystem;
    this.delegate = delegate;
  }

  Path delegate() {
    return delegate;
  }

  private Path wrap(Path path) {
    return path == null ? null : fileSystem.wrap(path);
  }

  private Path unwrap(Path path) {
    return fileSystem.unwrap(path);
  }

  @Override
  public FileSystem getFileSystem() {
    return fileSystem;
  }

  @Override
  public boolean isAbsolute() {
    return delegate.isAbsolute();
  }

  @Override
  public Path getRoot() {
    return wrap(delegate.getRoot());
  }

  @Override
  public Path getFileName() {
    return wrap(delegate.getFileName());
  }

  @Override
  public Path getParent() {
    return wrap(delegate.getParent());
  }

  @Override
  public int getNameCount() {
    return delegate.getNameCount();
  }

  @Override
  public Path getName(int index) {
    return wrap(delegate.getName(index));
  }

  @Override
  public Path subpath(int beginIndex, int endIndex) {
    return wrap(delegate.subpath(beginIndex, endIndex));
  }

  @Override
  public boolean startsWith(Path other) {
    try {
      return delegate.startsWith(unwrap(other));
    } catch (java.nio.file.ProviderMismatchException e) {
      return false;
    }
  }

  @Override
  public boolean startsWith(String other) {
    return delegate.startsWith(other);
  }

  @Override
  public boolean endsWith(Path other) {
    try {
      return delegate.endsWith(unwrap(other));
    } catch (java.nio.file.ProviderMismatchException e) {
      return false;
    }
  }

  @Override
  public boolean endsWith(String other) {
    return delegate.endsWith(other);
  }

  @Override
  public Path normalize() {
    return wrap(delegate.normalize());
  }

  @Override
  public Path resolve(Path other) {
    return wrap(delegate.resolve(unwrap(other)));
  }

  @Override
  public Path resolve(String other) {
    return wrap(delegate.resolve(other));
  }

  @Override
  public Path resolveSibling(Path other) {
    return wrap(delegate.resolveSibling(unwrap(other)));
  }

  @Override
  public Path resolveSibling(String other) {
    return wrap(delegate.resolveSibling(other));
  }

  @Override
  public Path relativize(Path other) {
    return wrap(delegate.relativize(unwrap(other)));
  }

  @Override
  public URI toUri() {
    throw new UnsupportedOperationException(
        "Workspace paths cannot be reconstructed from native URIs; use nativePath explicitly");
  }

  @Override
  public Path toAbsolutePath() {
    return wrap(delegate.toAbsolutePath());
  }

  @Override
  public Path toRealPath(LinkOption... options) throws IOException {
    Path checked =
        fileSystem.checked(
            this, !java.util.Arrays.asList(options).contains(LinkOption.NOFOLLOW_LINKS));
    return wrap(checked.toRealPath(options));
  }

  @Override
  public File toFile() {
    throw new UnsupportedOperationException("Use WorkspaceStorage.nativePath explicitly");
  }

  @Override
  public WatchKey register(
      WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers)
      throws IOException {
    return fileSystem.checked(this, true).register(watcher, events, modifiers);
  }

  @Override
  public Iterator<Path> iterator() {
    Iterator<Path> iterator = delegate.iterator();
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return iterator.hasNext();
      }

      @Override
      public Path next() {
        return wrap(iterator.next());
      }
    };
  }

  @Override
  public int compareTo(Path other) {
    return delegate.compareTo(unwrap(other));
  }

  @Override
  public boolean equals(Object other) {
    // Native Path implementations do not accept wrapper paths. Keep equality symmetric; use
    // startsWith/resolve/relativize (which explicitly accept native operands) for boundary checks.
    return other instanceof WorkspacePath path
        && fileSystem == path.fileSystem
        && delegate.equals(path.delegate);
  }

  @Override
  public int hashCode() {
    return 31 * System.identityHashCode(fileSystem) + delegate.hashCode();
  }

  @Override
  public String toString() {
    return delegate.toString();
  }
}
