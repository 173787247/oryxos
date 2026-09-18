package io.oryxos.core.workspace;

import io.oryxos.core.io.AtomicFiles;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Durable, non-expiring reservation for managed workspace edits. All writers must participate. A
 * crashed writer leaves the reservation for offline recovery; a timeout never fences POSIX IO. This
 * serializes writers, not readers, and is not a multi-file atomic visibility guarantee.
 */
public final class WorkspacePublication {
  public static final String RESERVATION = ".workspace-write";
  public static final String REVISION = ".workspace-revision";
  private final Path root;

  public WorkspacePublication(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  /** Opaque workspace generation for If-Match. Missing means a workspace never managed here. */
  public String revision() throws IOException {
    Path path = root.resolve(REVISION);
    if (Files.isSymbolicLink(path)) {
      throw new IOException("Invalid workspace revision");
    }
    try {
      return Files.readString(path).strip();
    } catch (java.nio.file.NoSuchFileException missing) {
      return "initial";
    }
  }

  public Reservation begin(String expectedRevision, String operation) throws IOException {
    Path directory = root.resolve(RESERVATION);
    try {
      Files.createDirectory(directory);
    } catch (FileAlreadyExistsException occupied) {
      throw new ConflictException("工作区正在修改或存在中断提交；请重试或按恢复指南处理");
    }
    String token = UUID.randomUUID().toString();
    Reservation reservation = new Reservation(directory, token);
    // If creation of owner fails, retain the reservation rather than pretending it was not
    // acquired.
    AtomicFiles.writeString(directory.resolve("owner"), token + "\n");
    AtomicFiles.writeString(directory.resolve("operation"), operation + "\n");
    String current = revision();
    if (expectedRevision != null && !expectedRevision.equals(current)) {
      reservation.release();
      throw new ConflictException("工作区版本已变化，请刷新后重新提交");
    }
    AtomicFiles.writeString(directory.resolve("before-revision"), current + "\n");
    return reservation;
  }

  public final class Reservation {
    private final Path directory;
    private final String token;
    private boolean released;

    private Reservation(Path directory, String token) {
      this.directory = directory;
      this.token = token;
    }

    public String commit() throws IOException {
      verifyOwner();
      String revision = UUID.randomUUID().toString();
      AtomicFiles.writeString(root.resolve(REVISION), revision + "\n");
      AtomicFiles.writeString(directory.resolve("committed-revision"), revision + "\n");
      release();
      return revision;
    }

    /**
     * A client error does not prove rollback. Invalidate stale editors and retain diagnostic
     * evidence.
     */
    public String rejectUnverified() throws IOException {
      verifyOwner();
      String revision = UUID.randomUUID().toString();
      AtomicFiles.writeString(directory.resolve("failed"), "rejected-unverified\n");
      AtomicFiles.writeString(root.resolve(REVISION), revision + "\n");
      AtomicFiles.writeString(directory.resolve("committed-revision"), revision + "\n");
      Files.move(
          directory,
          root.resolve(".workspace-rejected-" + token),
          java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      released = true;
      return revision;
    }

    /** Only use when validation rejected the request and its service has rolled back changes. */
    public void cancel() throws IOException {
      release();
    }

    /** Preserve on unexpected failure for offline diagnosis/recovery. No time-based takeover. */
    public void failed() throws IOException {
      verifyOwner();
      AtomicFiles.writeString(directory.resolve("failed"), "requires-offline-recovery\n");
    }

    private void verifyOwner() throws IOException {
      if (released
          || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
          || !token.equals(Files.readString(directory.resolve("owner")).strip())) {
        throw new IOException("Workspace publication ownership changed");
      }
    }

    private void release() throws IOException {
      verifyOwner();
      // Known files only: unexpected recovery data must not be destroyed.
      for (String name :
          new String[] {"operation", "before-revision", "committed-revision", "failed"}) {
        Files.deleteIfExists(directory.resolve(name));
      }
      Files.delete(directory.resolve("owner"));
      Files.delete(directory);
      released = true;
    }
  }

  public static final class ConflictException extends IOException {
    public ConflictException(String message) {
      super(message);
    }
  }
}
