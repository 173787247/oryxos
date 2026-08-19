package io.oryxos.knowledge.watch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.embedding.TextEmbedder;
import io.oryxos.core.knowledge.model.DocumentState;
import io.oryxos.knowledge.index.KnowledgeIndexService;
import io.oryxos.knowledge.store.InMemoryChunkStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T039：热加载与对账（FR-010 / US4）——直接调 reconcile/removeBase（不依赖真实事件时序， 与 WorkspaceWatcherTest
 * 同款测法）：新目录发现、文档改删收敛、非法目录告警跳过、启动对账。
 */
class KnowledgeWatcherTest {

  @TempDir Path root;

  private Path kbRoot;
  private InMemoryChunkStore store;
  private KnowledgeIndexService indexService;
  private KnowledgeWatcher watcher;

  @BeforeEach
  void setUp() throws IOException {
    kbRoot = Files.createDirectories(root.resolve("knowledge"));
    store = new InMemoryChunkStore();
    indexService = new KnowledgeIndexService(kbRoot, store, TestEmbedder::new, Runnable::run);
    watcher = new KnowledgeWatcher(root, indexService, Runnable::run);
  }

  @Test
  @DisplayName("新库目录 → 对账发现并索引；文档修改/删除 → 收敛")
  void reconcileDiscoversIndexesAndConverges() throws IOException {
    Path kb = knowledgeBase("ops", "运维手册");
    Files.writeString(kb.resolve("a.md"), "# 磁盘告警\n\n内容一");

    watcher.reconcileQuietly(kb);
    assertEquals(DocumentState.READY, indexService.status("ops").get(0).state());

    // 修改：指纹变化 → 重索引
    Files.writeString(kb.resolve("a.md"), "# 磁盘告警\n\n改过的内容");
    watcher.reconcileQuietly(kb);
    List<io.oryxos.knowledge.store.ChunkStore.ChunkRecord> chunks =
        store.chunks("ops", indexService.activeGeneration("ops"));
    assertTrue(chunks.stream().anyMatch(c -> c.content().contains("改过的内容")), "修改后的内容可被命中");

    // 删除文档：索引行与片段一并清（SC-006 不再命中）
    Files.delete(kb.resolve("a.md"));
    watcher.reconcileQuietly(kb);
    assertTrue(indexService.status("ops").isEmpty());
    assertTrue(store.chunks("ops", indexService.activeGeneration("ops")).isEmpty());
  }

  @Test
  @DisplayName("指纹未变的文档不重复索引（对账幂等且廉价）")
  void unchangedDocumentIsNotReindexed() throws IOException {
    Path kb = knowledgeBase("ops", "运维手册");
    Files.writeString(kb.resolve("a.md"), "# 内容");
    watcher.reconcileQuietly(kb);
    var first = indexService.status("ops").get(0).indexedAt();

    watcher.reconcileQuietly(kb);

    assertEquals(first, indexService.status("ops").get(0).indexedAt(), "指纹未变不得重建片段");
  }

  @Test
  @DisplayName("非法目录（缺清单/名称不一致）跳过告警，不影响其他库；远程后端库不做本地对账")
  void invalidAndRemoteDirectoriesAreSkipped() throws IOException {
    Path bad = Files.createDirectories(kbRoot.resolve("no-manifest"));
    Files.writeString(bad.resolve("a.md"), "# 内容");
    watcher.reconcileQuietly(bad); // 不抛，仅 WARN
    assertTrue(store.allDocuments("no-manifest").isEmpty());

    Path remote = Files.createDirectories(kbRoot.resolve("remote-kb"));
    Files.writeString(
        remote.resolve("KNOWLEDGE.md"),
        "---\nname: remote-kb\ndescription: 远程库\nbackend: ragflow\n---\n");
    watcher.reconcileQuietly(remote);
    assertTrue(store.allDocuments("remote-kb").isEmpty(), "远程后端无本地索引");
  }

  @Test
  @DisplayName("坏文件（扫描件等）WARN 跳过，不拖垮整库对账")
  void badFileDoesNotBreakReconcile() throws IOException {
    Path kb = knowledgeBase("ops", "运维手册");
    Files.writeString(kb.resolve("good.md"), "# 正常内容");
    Files.writeString(kb.resolve("bad.pdf"), "not a real pdf"); // 解析失败

    watcher.reconcileQuietly(kb);

    assertTrue(
        indexService.status("ops").stream()
            .anyMatch(s -> s.relPath().equals("good.md") && s.state() == DocumentState.READY),
        "好文件照常索引");
  }

  @Test
  @DisplayName("库目录被删 → 索引与片段清理")
  void removedBaseIsCleanedUp() throws IOException {
    Path kb = knowledgeBase("ops", "运维手册");
    Files.writeString(kb.resolve("a.md"), "# 内容");
    watcher.reconcileQuietly(kb);

    watcher.removeBase(kb);

    assertTrue(store.allDocuments("ops").isEmpty());
  }

  private Path knowledgeBase(String name, String description) throws IOException {
    Path dir = Files.createDirectories(kbRoot.resolve(name));
    Files.writeString(
        dir.resolve("KNOWLEDGE.md"),
        "---\nname: " + name + "\ndescription: " + description + "\n---\n");
    return dir;
  }

  private static final class TestEmbedder implements TextEmbedder {
    @Override
    public float[] embed(String text) {
      float[] vector = new float[4];
      for (int i = 0; i < text.length(); i++) {
        vector[text.charAt(i) % 4] += 1;
      }
      return vector;
    }

    @Override
    public String modelId() {
      return "test/v1";
    }

    @Override
    public int dimensions() {
      return 4;
    }
  }
}
