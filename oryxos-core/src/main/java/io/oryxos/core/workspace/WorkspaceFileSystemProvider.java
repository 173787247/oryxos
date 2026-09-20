package io.oryxos.core.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Delegates standard NIO operations after a fail-closed health check. */
final class WorkspaceFileSystemProvider extends FileSystemProvider {
  private static final String ATTRIBUTE_VIEW_NAME_METHOD = "name";
  private final WorkspaceFileSystem fileSystem;
  private final FileSystemProvider delegate;

  WorkspaceFileSystemProvider(WorkspaceFileSystem fileSystem, FileSystemProvider delegate) {
    this.fileSystem = fileSystem;
    this.delegate = delegate;
  }

  private Path unwrap(Path path) throws IOException {
    return fileSystem.checked(path, true);
  }

  private Path entry(Path path) throws IOException {
    return fileSystem.checked(path, false);
  }

  private Path attributesPath(Path path, LinkOption... options) throws IOException {
    return fileSystem.checked(
        path, !java.util.Arrays.asList(options).contains(LinkOption.NOFOLLOW_LINKS));
  }

  private void check() throws IOException {
    fileSystem.checkHealth();
  }

  @Override
  public String getScheme() {
    return "oryxos-workspace";
  }

  @Override
  public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
    throw new UnsupportedOperationException("Use WorkspaceStorageProvider.open");
  }

  @Override
  public FileSystem getFileSystem(URI uri) {
    throw new UnsupportedOperationException("Use WorkspaceStorageProvider.open");
  }

  @Override
  public Path getPath(URI uri) {
    throw new UnsupportedOperationException("Use WorkspaceStorage.resolve");
  }

  @Override
  public SeekableByteChannel newByteChannel(
      Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
    check();
    SeekableByteChannel channel =
        delegate.newByteChannel(
            fileSystem.checked(path, !options.contains(LinkOption.NOFOLLOW_LINKS)), options, attrs);
    return new SeekableByteChannel() {
      @Override
      public int read(ByteBuffer destination) throws IOException {
        check();
        return channel.read(destination);
      }

      @Override
      public int write(ByteBuffer source) throws IOException {
        check();
        return channel.write(source);
      }

      @Override
      public long position() throws IOException {
        check();
        return channel.position();
      }

      @Override
      public SeekableByteChannel position(long position) throws IOException {
        check();
        channel.position(position);
        return this;
      }

      @Override
      public long size() throws IOException {
        check();
        return channel.size();
      }

      @Override
      public SeekableByteChannel truncate(long size) throws IOException {
        check();
        channel.truncate(size);
        return this;
      }

      @Override
      public boolean isOpen() {
        return channel.isOpen();
      }

      // Close must remain possible even after health failure, otherwise callers leak handles.
      @Override
      public void close() throws IOException {
        channel.close();
      }
    };
  }

  @Override
  public DirectoryStream<Path> newDirectoryStream(
      Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
    check();
    DirectoryStream<Path> stream =
        delegate.newDirectoryStream(
            unwrap(dir),
            child -> {
              check();
              return filter.accept(fileSystem.wrap(child));
            });
    return new DirectoryStream<>() {
      @Override
      public Iterator<Path> iterator() {
        Iterator<Path> iterator = stream.iterator();
        return new Iterator<>() {
          private void checkIteration() {
            try {
              check();
            } catch (IOException e) {
              throw new DirectoryIteratorException(e);
            }
          }

          @Override
          public boolean hasNext() {
            checkIteration();
            return iterator.hasNext();
          }

          @Override
          public Path next() {
            checkIteration();
            return fileSystem.wrap(iterator.next());
          }
        };
      }

      @Override
      public void close() throws IOException {
        stream.close();
      }
    };
  }

  @Override
  public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
    check();
    delegate.createDirectory(entry(dir), attrs);
  }

  @Override
  public void delete(Path path) throws IOException {
    check();
    delegate.delete(entry(path));
  }

  @Override
  public void copy(Path source, Path target, CopyOption... options) throws IOException {
    check();
    delegate.copy(
        fileSystem.checked(
            source, !java.util.Arrays.asList(options).contains(LinkOption.NOFOLLOW_LINKS)),
        entry(target),
        options);
  }

  @Override
  public void move(Path source, Path target, CopyOption... options) throws IOException {
    check();
    delegate.move(entry(source), entry(target), options);
  }

  @Override
  public boolean isSameFile(Path first, Path second) throws IOException {
    check();
    return delegate.isSameFile(unwrap(first), unwrap(second));
  }

  @Override
  public boolean isHidden(Path path) throws IOException {
    check();
    return delegate.isHidden(unwrap(path));
  }

  @Override
  public FileStore getFileStore(Path path) throws IOException {
    check();
    return delegate.getFileStore(unwrap(path));
  }

  @Override
  public void checkAccess(Path path, AccessMode... modes) throws IOException {
    check();
    delegate.checkAccess(unwrap(path), modes);
  }

  @Override
  public <V extends FileAttributeView> V getFileAttributeView(
      Path path, Class<V> type, LinkOption... options) {
    Path checked;
    try {
      checked = attributesPath(path, options);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    V view = delegate.getFileAttributeView(checked, type, options);
    if (view == null) {
      return null;
    }
    // All supported attribute-view interfaces retain the guard even if cached by a consumer.
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              if (method.getDeclaringClass() != Object.class
                  && !ATTRIBUTE_VIEW_NAME_METHOD.equals(method.getName())) {
                attributesPath(path, options);
              }
              try {
                return method.invoke(view, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            }));
  }

  @Override
  public <A extends BasicFileAttributes> A readAttributes(
      Path path, Class<A> type, LinkOption... options) throws IOException {
    check();
    return delegate.readAttributes(attributesPath(path, options), type, options);
  }

  @Override
  public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
      throws IOException {
    check();
    return delegate.readAttributes(attributesPath(path, options), attributes, options);
  }

  @Override
  public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
      throws IOException {
    check();
    delegate.setAttribute(attributesPath(path, options), attribute, value, options);
  }

  @Override
  public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs)
      throws IOException {
    Path nativeLink = entry(link);
    Path nativeTarget = fileSystem.unwrap(target);
    Path parent = nativeLink.getParent();
    if (parent == null) {
      throw new IOException("Symbolic link entry requires a parent directory");
    }
    fileSystem.checked(
        nativeTarget.isAbsolute() ? nativeTarget : parent.resolve(nativeTarget), true);
    delegate.createSymbolicLink(nativeLink, nativeTarget, attrs);
  }

  @Override
  public void createLink(Path link, Path existing) throws IOException {
    check();
    delegate.createLink(entry(link), fileSystem.checked(existing, true));
  }

  @Override
  public Path readSymbolicLink(Path link) throws IOException {
    check();
    return fileSystem.wrap(delegate.readSymbolicLink(entry(link)));
  }
}
