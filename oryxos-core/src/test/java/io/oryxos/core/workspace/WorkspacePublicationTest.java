package io.oryxos.core.workspace;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacePublicationTest {
  @TempDir Path root;

  @Test
  void independentWritersConflictAndOldRevisionCannotOverwrite() throws Exception {
    var first = new WorkspacePublication(root);
    var second = new WorkspacePublication(root);
    var reservation = first.begin("initial", "test");
    assertThrows(WorkspacePublication.ConflictException.class, () -> second.begin(null, "test"));
    String revision = reservation.commit();
    assertEquals(revision, second.revision());
    assertThrows(
        WorkspacePublication.ConflictException.class, () -> second.begin("initial", "test"));
    second.begin(revision, "test").cancel();
    assertFalse(Files.exists(root.resolve(WorkspacePublication.RESERVATION)));
  }

  @Test
  void failedReservationNeverExpiresOrGetsReclaimed() throws Exception {
    var publication = new WorkspacePublication(root);
    publication.begin(null, "test").failed();
    assertThrows(
        WorkspacePublication.ConflictException.class,
        () -> new WorkspacePublication(root).begin(null, "replacement"));
    assertTrue(Files.exists(root.resolve(WorkspacePublication.RESERVATION).resolve("failed")));
  }

  @Test
  void multiFileReplacementHasNoRemainingJournalOnSuccess() throws Exception {
    Path a = root.resolve("AGENT.md");
    Files.writeString(a, "before");
    RecoverableFiles.write(
        root, Map.of(a, "after".getBytes(), root.resolve("scripts/a.py"), "script".getBytes()));
    assertEquals("after", Files.readString(a));
    assertEquals("script", Files.readString(root.resolve("scripts/a.py")));
    assertFalse(Files.exists(root.resolve(RecoverableFiles.JOURNAL)));
  }

  @Test
  void offlineRecoveryRestoresInterruptedPublicationAndRemovesCreatedFiles() throws Exception {
    Path a = root.resolve("AGENT.md");
    Path b = root.resolve("new.txt");
    Files.writeString(a, "partial-new-version");
    Files.writeString(b, "new");
    Path journal = Files.createDirectory(root.resolve(RecoverableFiles.JOURNAL));
    Files.writeString(journal.resolve("0.old"), "original");
    Files.writeString(
        journal.resolve("manifest"),
        "0\ttrue\t" + encoded("AGENT.md") + "\n1\tfalse\t" + encoded("new.txt") + "\n");
    assertThrows(
        WorkspacePublication.ConflictException.class,
        () -> RecoverableFiles.write(root, Map.of(a, "overwrite".getBytes())));
    RecoverableFiles.recoverOffline(root);
    assertEquals("original", Files.readString(a));
    assertFalse(Files.exists(b));
    assertFalse(Files.exists(journal));
  }

  private static String encoded(String value) {
    return Base64.getEncoder()
        .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
