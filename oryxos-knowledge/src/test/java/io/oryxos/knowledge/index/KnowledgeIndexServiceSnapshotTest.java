package io.oryxos.knowledge.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxos.core.embedding.TextEmbedder;
import io.oryxos.knowledge.store.InMemoryChunkStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeIndexServiceSnapshotTest {

  @TempDir Path root;

  @Test
  void importUsesOneSnapshotForParserAndHashWhenSourceIsReplaced() throws Exception {
    Path source = createSource("old import content");
    InMemoryChunkStore store = new InMemoryChunkStore();
    AtomicReference<Path> snapshot = new AtomicReference<>();
    KnowledgeIndexService service =
        service(store, replacingParser(source, "new import content", snapshot));

    service.importDocument("ops", "guide.md");

    var document = store.findDocument("ops", "guide.md", 0).orElseThrow();
    assertThat(document.sha256()).isEqualTo(sha256("old import content"));
    assertThat(store.chunks("ops", 0)).extracting(c -> c.content()).contains("old import content");
    assertThat(Files.readString(source)).isEqualTo("new import content");
    assertThat(snapshot.get()).doesNotExist();
  }

  @Test
  void rebuildUsesOneSnapshotForParserAndHashWhenSourceIsReplaced() throws Exception {
    Path source = createSource("old rebuild content");
    InMemoryChunkStore store = new InMemoryChunkStore();
    AtomicReference<Path> snapshot = new AtomicReference<>();
    KnowledgeIndexService service =
        service(store, replacingParser(source, "new rebuild content", snapshot));

    service.rebuild("ops");

    var document = store.findDocument("ops", "guide.md", 1).orElseThrow();
    assertThat(document.sha256()).isEqualTo(sha256("old rebuild content"));
    assertThat(store.chunks("ops", 1)).extracting(c -> c.content()).contains("old rebuild content");
    assertThat(Files.readString(source)).isEqualTo("new rebuild content");
    assertThat(snapshot.get()).doesNotExist();
  }

  @Test
  void parserFailureDeletesSnapshotAndDoesNotCommitDocument() throws Exception {
    createSource("invalid content");
    InMemoryChunkStore store = new InMemoryChunkStore();
    AtomicReference<Path> snapshot = new AtomicReference<>();
    KnowledgeIndexService service = service(store, failingParser(snapshot));

    assertThatThrownBy(() -> service.importDocument("ops", "guide.md"))
        .isInstanceOf(io.oryxos.core.knowledge.KnowledgeImportException.class)
        .hasMessageContaining("parser rejected");

    assertThat(snapshot.get()).doesNotExist();
    assertThat(store.allDocuments("ops")).isEmpty();
    assertThat(store.chunks("ops", 0)).isEmpty();
  }

  @Test
  void cleanupFailureIsSuppressedUnderOriginalParserFailure() throws Exception {
    createSource("invalid content");
    InMemoryChunkStore store = new InMemoryChunkStore();
    AtomicReference<Path> snapshot = new AtomicReference<>();
    KnowledgeIndexService service =
        new KnowledgeIndexService(
            root,
            store,
            this::embedder,
            Runnable::run,
            List.of(failingParser(snapshot)),
            path -> {
              throw new IOException("cleanup failed");
            });

    try {
      assertThatThrownBy(() -> service.importDocument("ops", "guide.md"))
          .isInstanceOf(io.oryxos.core.knowledge.KnowledgeImportException.class)
          .hasMessageContaining("parser rejected")
          .satisfies(
              failure -> {
                assertThat(failure.getSuppressed()).hasSize(1);
                assertThat(failure.getSuppressed()[0])
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("cleanup failed");
              });
    } finally {
      Files.deleteIfExists(snapshot.get());
    }
  }

  private Path createSource(String content) throws IOException {
    Path directory = Files.createDirectories(root.resolve("ops"));
    Path source = directory.resolve("guide.md");
    Files.writeString(source, content);
    return source;
  }

  private KnowledgeIndexService service(InMemoryChunkStore store, DocumentParser parser) {
    return new KnowledgeIndexService(root, store, this::embedder, Runnable::run, List.of(parser));
  }

  private TextEmbedder embedder() {
    return new TextEmbedder() {
      @Override
      public float[] embed(String text) {
        return new float[] {1};
      }

      @Override
      public String modelId() {
        return "snapshot-test";
      }

      @Override
      public int dimensions() {
        return 1;
      }
    };
  }

  private static DocumentParser failingParser(AtomicReference<Path> observedSnapshot) {
    return new DocumentParser() {
      @Override
      public boolean supports(String fileName) {
        return fileName.endsWith(".md");
      }

      @Override
      public List<ParsedUnit> parse(Path snapshot) {
        observedSnapshot.set(snapshot);
        throw new io.oryxos.core.knowledge.KnowledgeImportException("parser rejected");
      }
    };
  }

  private static DocumentParser replacingParser(
      Path source, String replacement, AtomicReference<Path> observedSnapshot) {
    return new DocumentParser() {
      @Override
      public boolean supports(String fileName) {
        return fileName.endsWith(".md");
      }

      @Override
      public List<ParsedUnit> parse(Path snapshot) {
        try {
          observedSnapshot.set(snapshot);
          String captured = Files.readString(snapshot);
          Files.writeString(source, replacement);
          return List.of(new ParsedUnit(captured, null));
        } catch (IOException e) {
          throw new IllegalStateException(e);
        }
      }
    };
  }

  private static String sha256(String content) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
  }
}
