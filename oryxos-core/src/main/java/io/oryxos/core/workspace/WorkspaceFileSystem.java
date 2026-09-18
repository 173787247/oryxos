package io.oryxos.core.workspace;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Set;

final class WorkspaceFileSystem extends FileSystem {
  @FunctionalInterface
  interface HealthCheck {
    void check() throws IOException;
  }

  private final FileSystem delegate;
  private final Path root;
  private final HealthCheck healthCheck;
  private final WorkspaceFileSystemProvider provider;

  WorkspaceFileSystem(Path root, HealthCheck healthCheck) {
    this(root, healthCheck, root.getFileSystem().provider());
  }

  WorkspaceFileSystem(Path root, HealthCheck healthCheck, FileSystemProvider delegateProvider) {
    this.delegate = root.getFileSystem();
    this.root = root.toAbsolutePath().normalize();
    this.healthCheck = healthCheck;
    this.provider = new WorkspaceFileSystemProvider(this, delegateProvider);
  }

  Path checked(Path path, boolean followFinalLink) throws IOException {
    checkHealth();
    Path nativePath = unwrap(path).toAbsolutePath().normalize();
    Path realRoot = root.toRealPath();
    if (!nativePath.startsWith(root) && !nativePath.startsWith(realRoot)) {
      throw new java.nio.file.AccessDeniedException("Path escapes workspace root");
    }
    Path projected =
        !followFinalLink && !nativePath.equals(root) && !nativePath.equals(realRoot)
            ? nativePath.getParent()
            : nativePath;
    try {
      io.oryxos.core.fs.RealPathBoundary.requireWithin(root, projected);
    } catch (IllegalArgumentException | java.io.UncheckedIOException failure) {
      throw new java.nio.file.AccessDeniedException(
          "Real path escapes workspace root or is unavailable");
    }
    return nativePath;
  }

  void checkHealth() throws IOException {
    healthCheck.check();
  }

  Path wrap(Path path) {
    return new WorkspacePath(this, path);
  }

  Path unwrap(Path path) {
    if (path instanceof WorkspacePath wrapped) {
      if (wrapped.getFileSystem() != this) {
        throw new ProviderMismatchException("Different workspace storage instance");
      }
      return wrapped.delegate();
    }
    if (path.getFileSystem() != delegate) {
      throw new ProviderMismatchException("Different filesystem");
    }
    return path;
  }

  @Override
  public FileSystemProvider provider() {
    return provider;
  }

  /** The workspace borrows the delegate and must never close a process-wide filesystem. */
  @Override
  public void close() {}

  @Override
  public boolean isOpen() {
    return delegate.isOpen();
  }

  @Override
  public boolean isReadOnly() {
    return delegate.isReadOnly();
  }

  @Override
  public String getSeparator() {
    return delegate.getSeparator();
  }

  @Override
  public Iterable<Path> getRootDirectories() {
    var result = new ArrayList<Path>();
    delegate.getRootDirectories().forEach(path -> result.add(wrap(path)));
    return result;
  }

  @Override
  public Iterable<FileStore> getFileStores() {
    throw new UnsupportedOperationException("Use provider.getFileStore(path) for guarded access");
  }

  @Override
  public Set<String> supportedFileAttributeViews() {
    return delegate.supportedFileAttributeViews();
  }

  @Override
  public Path getPath(String first, String... more) {
    return wrap(delegate.getPath(first, more));
  }

  @Override
  public PathMatcher getPathMatcher(String syntaxAndPattern) {
    PathMatcher matcher = delegate.getPathMatcher(syntaxAndPattern);
    return path -> matcher.matches(unwrap(path));
  }

  @Override
  public UserPrincipalLookupService getUserPrincipalLookupService() {
    return delegate.getUserPrincipalLookupService();
  }

  @Override
  public WatchService newWatchService() throws IOException {
    checkHealth();
    return delegate.newWatchService();
  }
}
