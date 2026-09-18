package io.oryxos.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxos.core.embedding.TextEmbedder;
import io.oryxos.core.knowledge.KnowledgeManifest;
import io.oryxos.core.workspace.SharedPosixWorkspaceStorageProvider;
import io.oryxos.core.workspace.WorkspaceStorage;
import io.oryxos.knowledge.index.KnowledgeIndexService;
import io.oryxos.knowledge.store.ChunkStore;
import io.oryxos.knowledge.store.InMemoryChunkStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceKnowledgePluginTest {

  @TempDir Path temporaryDirectory;

  @Test
  void selectedWorkspaceOwnsKnowledgeFilesAndFailsClosedWhenIdentityChanges() throws Exception {
    Path configuredRoot = Files.createDirectories(temporaryDirectory.resolve("configured-local"));
    Path fallbackKnowledge = Files.createDirectories(configuredRoot.resolve("knowledge/ops"));
    Files.writeString(fallbackKnowledge.resolve("fallback.md"), "fallback-only content");

    Path materializedRoot =
        Files.createDirectories(temporaryDirectory.resolve("materialized-shared-workspace"));
    Files.writeString(materializedRoot.resolve(".workspace-id"), "shared-fixture");

    try (WorkspaceStorage storage =
        new SharedPosixWorkspaceStorageProvider().open(materializedRoot, "shared-fixture")) {
      Path knowledgeRoot = storage.root().resolve("knowledge");
      Files.createDirectories(knowledgeRoot);
      InMemoryChunkStore store = new InMemoryChunkStore();
      KnowledgeIndexService indexService =
          new KnowledgeIndexService(
              knowledgeRoot, store, WorkspaceKnowledgePluginTest::embedder, Runnable::run);
      LocalKnowledgeBackend backend =
          new LocalKnowledgeBackend(
              knowledgeRoot, store, indexService, WorkspaceKnowledgePluginTest::embedder);

      backend.createBase("ops", "selected workspace");
      Path selectedSource = knowledgeRoot.resolve("ops/selected.md");
      Files.writeString(selectedSource, "selected-provider content");
      backend.importDocument("ops", "selected.md");

      assertThat(storage.root().getFileSystem().provider().getScheme())
          .isEqualTo("oryxos-workspace");
      assertThat(KnowledgeManifest.read(knowledgeRoot.resolve("ops")).description())
          .isEqualTo("selected workspace");
      assertThat(store.findDocument("ops", "selected.md", 0)).isPresent();
      assertThat(store.chunks("ops", 0))
          .extracting(ChunkStore.ChunkRecord::content)
          .contains("selected-provider content")
          .doesNotContain("fallback-only content");
      assertThat(fallbackKnowledge.resolve(KnowledgeManifest.FILE)).doesNotExist();

      Files.writeString(materializedRoot.resolve(".workspace-id"), "wrong-workspace");

      assertThatThrownBy(() -> backend.updateBase("ops", "must not be written"))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> backend.createBase("after-identity-loss", "must fail"))
          .isInstanceOf(RuntimeException.class);
      assertThatThrownBy(() -> Files.readString(selectedSource))
          .isInstanceOf(java.io.IOException.class)
          .hasMessageContaining("identity mismatch");
      assertThat(fallbackKnowledge.resolve(KnowledgeManifest.FILE)).doesNotExist();
      assertThat(configuredRoot.resolve("knowledge/after-identity-loss")).doesNotExist();
      assertThat(materializedRoot.resolve("knowledge/after-identity-loss")).doesNotExist();
    }
  }

  private static TextEmbedder embedder() {
    return new TextEmbedder() {
      @Override
      public float[] embed(String text) {
        return new float[] {1};
      }

      @Override
      public String modelId() {
        return "workspace-plugin-test";
      }

      @Override
      public int dimensions() {
        return 1;
      }
    };
  }
}
