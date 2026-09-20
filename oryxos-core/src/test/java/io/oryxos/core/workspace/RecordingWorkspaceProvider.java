package io.oryxos.core.workspace;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;

/**
 * Observable replacement provider: records actual delegated operation entry, never health checks.
 */
final class RecordingWorkspaceProvider extends FileSystemProvider {
  private final FileSystemProvider delegate;
  final Set<String> operations = new HashSet<>();
  String failOperation;

  RecordingWorkspaceProvider(FileSystemProvider delegate) {
    this.delegate = delegate;
  }

  void record(String operation) {
    operations.add(operation);
    if (operation.equals(failOperation)) {
      throw new UnsupportedOperationException("injected missing capability");
    }
  }

  @Override
  public String getScheme() {
    return "recording";
  }

  @Override
  public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
    throw new UnsupportedOperationException();
  }

  @Override
  public FileSystem getFileSystem(URI uri) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Path getPath(URI uri) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SeekableByteChannel newByteChannel(
      Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
    record("newByteChannel");
    return delegate.newByteChannel(path, options, attrs);
  }

  @Override
  public DirectoryStream<Path> newDirectoryStream(
      Path path, DirectoryStream.Filter<? super Path> filter) throws IOException {
    record("newDirectoryStream");
    return delegate.newDirectoryStream(path, filter);
  }

  @Override
  public void createDirectory(Path path, FileAttribute<?>... attrs) throws IOException {
    record("createDirectory");
    delegate.createDirectory(path, attrs);
  }

  @Override
  public void delete(Path path) throws IOException {
    record("delete");
    delegate.delete(path);
  }

  @Override
  public void copy(Path source, Path target, CopyOption... options) throws IOException {
    record("copy");
    delegate.copy(source, target, options);
  }

  @Override
  public void move(Path source, Path target, CopyOption... options) throws IOException {
    record("move");
    delegate.move(source, target, options);
  }

  @Override
  public boolean isSameFile(Path first, Path second) throws IOException {
    record("isSameFile");
    return delegate.isSameFile(first, second);
  }

  @Override
  public boolean isHidden(Path path) throws IOException {
    record("isHidden");
    return delegate.isHidden(path);
  }

  @Override
  public FileStore getFileStore(Path path) throws IOException {
    record("getFileStore");
    return delegate.getFileStore(path);
  }

  @Override
  public void checkAccess(Path path, AccessMode... modes) throws IOException {
    record("checkAccess");
    delegate.checkAccess(path, modes);
  }

  @Override
  public <A extends BasicFileAttributes> A readAttributes(
      Path path, Class<A> type, LinkOption... options) throws IOException {
    record("readAttributes");
    return delegate.readAttributes(path, type, options);
  }

  @Override
  public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
      throws IOException {
    record("readAttributes");
    return delegate.readAttributes(path, attributes, options);
  }

  @Override
  public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
      throws IOException {
    record("setAttribute");
    delegate.setAttribute(path, attribute, value, options);
  }

  @Override
  public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs)
      throws IOException {
    record("createSymbolicLink");
    delegate.createSymbolicLink(link, target, attrs);
  }

  @Override
  public void createLink(Path link, Path existing) throws IOException {
    record("createLink");
    delegate.createLink(link, existing);
  }

  @Override
  public Path readSymbolicLink(Path path) throws IOException {
    record("readSymbolicLink");
    return delegate.readSymbolicLink(path);
  }

  @Override
  public <V extends FileAttributeView> V getFileAttributeView(
      Path path, Class<V> type, LinkOption... options) {
    record("getFileAttributeView");
    return delegate.getFileAttributeView(path, type, options);
  }
}
