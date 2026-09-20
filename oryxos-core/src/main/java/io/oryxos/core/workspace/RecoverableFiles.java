package io.oryxos.core.workspace;

import io.oryxos.core.fs.RealPathBoundary;
import io.oryxos.core.io.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Recoverable multi-file replacement. Each file is atomic; the directory is not a snapshot. */
public final class RecoverableFiles {
  public static final String JOURNAL = ".workspace-transaction";

  private static final String MANIFEST = "manifest";
  private static final String COMMITTED = "committed";
  private static final int MANIFEST_COLUMNS = 3;

  private RecoverableFiles() {}

  public static void write(Path directory, Map<Path, byte[]> contents) throws IOException {
    Path root = directory.toAbsolutePath().normalize();
    Path journal = root.resolve(JOURNAL);
    try {
      Files.createDirectory(journal);
    } catch (FileAlreadyExistsException occupied) {
      throw new WorkspacePublication.ConflictException("Agent 存在进行中或中断提交，请先恢复");
    }
    List<Entry> entries = new ArrayList<>();
    boolean prepared = false;
    try {
      int index = 0;
      for (Map.Entry<Path, byte[]> content : contents.entrySet()) {
        Path target = RealPathBoundary.requireWithin(root, content.getKey());
        Path relative = root.relativize(target);
        if (relative.toString().isEmpty()
            || relative.startsWith(JOURNAL)
            || Files.isSymbolicLink(content.getKey())) {
          throw new IOException("Invalid transaction target");
        }
        Path parent = target.getParent();
        if (parent == null) {
          throw new IOException("Transaction target has no parent");
        }
        Files.createDirectories(parent);
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (exists && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("Transaction target is not a regular file");
        }
        Entry entry = new Entry(index++, relative.toString(), exists);
        if (exists) {
          Files.copy(target, journal.resolve(entry.index() + ".old"));
        }
        Files.write(journal.resolve(entry.index() + ".new"), content.getValue());
        entries.add(entry);
      }
      StringBuilder manifest = new StringBuilder();
      for (Entry entry : entries) {
        manifest
            .append(entry.index())
            .append('\t')
            .append(entry.existed())
            .append('\t')
            .append(
                Base64.getEncoder()
                    .encodeToString(entry.relative().getBytes(StandardCharsets.UTF_8)))
            .append('\n');
      }
      AtomicFiles.writeString(journal.resolve(MANIFEST), manifest.toString());
      prepared = true;
      for (Entry entry : entries) {
        Path target = RealPathBoundary.requireWithin(root, root.resolve(entry.relative()));
        Files.move(
            journal.resolve(entry.index() + ".new"),
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      }
      AtomicFiles.writeString(journal.resolve(COMMITTED), "true\n");
    } catch (IOException | RuntimeException failure) {
      if (prepared) {
        try {
          restore(root, journal, entries);
        } catch (IOException | RuntimeException restoreFailure) {
          failure.addSuppressed(restoreFailure);
          throw failure; // Leave all journal data if recovery could not complete.
        }
      }
      try {
        cleanup(journal);
      } catch (IOException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
    cleanup(journal); // Failure retains committed marker; maintenance recovery only cleans it.
  }

  /** Must only be called while all writers are stopped. Never called automatically at startup. */
  public static void recoverOffline(Path directory) throws IOException {
    Path root = directory.toAbsolutePath().normalize();
    Path journal = root.resolve(JOURNAL);
    if (!Files.isDirectory(journal, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("No regular transaction journal directory");
    }
    if (!Files.exists(journal.resolve(COMMITTED)) && Files.exists(journal.resolve(MANIFEST))) {
      List<Entry> entries = new ArrayList<>();
      for (String line : Files.readAllLines(journal.resolve(MANIFEST))) {
        String[] columns = line.split("\t", -1);
        if (columns.length != MANIFEST_COLUMNS) {
          throw new IOException("Invalid transaction manifest");
        }
        boolean validExistence = "true".equals(columns[1]) || "false".equals(columns[1]);
        if (!validExistence) {
          throw new IOException("Invalid transaction existence flag");
        }
        int index = Integer.parseInt(columns[0]);
        if (index < 0) {
          throw new IOException("Invalid transaction index");
        }
        entries.add(
            new Entry(
                index,
                new String(Base64.getDecoder().decode(columns[2]), StandardCharsets.UTF_8),
                Boolean.parseBoolean(columns[1])));
      }
      restore(root, journal, entries);
    }
    cleanup(journal);
  }

  private static void restore(Path root, Path journal, List<Entry> entries) throws IOException {
    for (Entry entry : entries) {
      Path target = RealPathBoundary.requireWithin(root, root.resolve(entry.relative()));
      if (entry.existed()) {
        // Keep backup intact until every restoration succeeds; recovery is repeatable.
        try (var input = Files.newInputStream(journal.resolve(entry.index() + ".old"))) {
          AtomicFiles.write(target, input::transferTo);
        }
      } else {
        Files.deleteIfExists(target);
      }
    }
  }

  private static void cleanup(Path journal) throws IOException {
    try (var files = Files.walk(journal)) {
      for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }

  private record Entry(int index, String relative, boolean existed) {}
}
