package io.oryxos.knowledge.watch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

  @Test
  @DisplayName("库内新建嵌套子目录 → 递归补挂监听（WatchService 非递归，不补挂则嵌套文档改动永不投递）")
  void nestedDirectoryCreateIsWatchedRecursively() throws IOException {
    Path kb = knowledgeBase("ops", "运维手册");
    Path nested = Files.createDirectories(kb.resolve("sub").resolve("deeper"));
    java.nio.file.WatchService watchService = kbRoot.getFileSystem().newWatchService();
    try {
      watcher.dispatch(
          watchService, kb, nested.getParent(), java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);

      assertTrue(watcher.watching(nested.getParent()), "新建子目录必须补挂监听");
      assertTrue(watcher.watching(nested), "子目录自带的更深层目录也必须补挂");
    } finally {
      watchService.close();
    }
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

  // ── 启动对账必须把被重启打断的 PENDING/INDEXING 拉回终态 ──
  //
  // 上传接口先落盘、再写 PENDING 行、最后把切分向量化交给执行器。进程在这个窗口被杀
  // （滚动升级、OOM、SIGKILL）之后，那行没有任何在飞任务，也没有别的机制把它拉回：
  // 文件在盘上、内容可读，却检索不到，failureReason 还是 null。

  @Test
  @DisplayName("启动对账：被重启打断的 PENDING 文档必须被重新驱动到就绪")
  void startupReconcileReDrivesADocumentLeftPendingByARestart() throws IOException {
    Path kb = knowledgeBase("ops", "运维手册");
    Files.writeString(kb.resolve("a.md"), "# 磁盘告警\n\n先查 inode 占用。");

    // 第一次启动：执行器只收任务不跑，等价于进程在后台段之前被杀
    ControllableExecutor crashed = new ControllableExecutor();
    KnowledgeIndexService firstBoot =
        new KnowledgeIndexService(kbRoot, store, TestEmbedder::new, crashed);
    firstBoot.importDocument("ops", "a.md");

    assertEquals(
        DocumentState.PENDING, firstBoot.status("ops").get(0).state(), "前提：任务没跑，行停在 PENDING");
    assertNull(firstBoot.status("ops").get(0).failureReason(), "前提：也没有失败原因");

    // 重启：新实例、新执行器，走启动对账那一条路
    ControllableExecutor afterBoot = new ControllableExecutor();
    KnowledgeIndexService restarted =
        new KnowledgeIndexService(kbRoot, store, TestEmbedder::new, afterBoot);
    restarted.reconcile("ops", true);

    assertEquals(
        DocumentState.PENDING, restarted.status("ops").get(0).state(), "对账只负责重新提交任务，推进由后台段完成");

    afterBoot.runAll();

    assertEquals(DocumentState.READY, restarted.status("ops").get(0).state(), "重启后必须收敛到就绪");
    assertTrue(
        store.chunks("ops", restarted.activeGeneration("ops")).stream()
            .anyMatch(c -> c.content().contains("inode")),
        "内容必须重新可被命中");
  }

  @Test
  @DisplayName("热加载对账不得重驱动在飞的 PENDING（同 JVM 内确实有任务在跑）")
  void hotReloadReconcileLeavesAnInFlightPendingAlone() throws IOException {
    Path kb = knowledgeBase("ops", "运维手册");
    Files.writeString(kb.resolve("a.md"), "# 磁盘告警\n\n先查 inode 占用。");

    ControllableExecutor inFlight = new ControllableExecutor();
    KnowledgeIndexService service =
        new KnowledgeIndexService(kbRoot, store, TestEmbedder::new, inFlight);
    service.importDocument("ops", "a.md");
    assertEquals(1, inFlight.queued(), "前提：后台任务确实在队列里");

    watcherWith(service).reconcileQuietly(kb);

    assertEquals(1, inFlight.queued(), "热加载路径不得再提交一次（否则与在飞任务重复劳动）");
  }

  @Test
  @DisplayName("反向：指纹与状态都没变的 READY 文档，启动对账不得重复导入")
  void startupReconcileDoesNotReimportASettledDocument() throws IOException {
    Path kb = knowledgeBase("ops", "运维手册");
    Files.writeString(kb.resolve("a.md"), "# 磁盘告警\n\n先查 inode 占用。");

    watcher.reconcileQuietly(kb, true);
    assertEquals(DocumentState.READY, indexService.status("ops").get(0).state());

    ControllableExecutor second = new ControllableExecutor();
    KnowledgeIndexService reopened =
        new KnowledgeIndexService(kbRoot, store, TestEmbedder::new, second);
    reopened.reconcile("ops", true);

    assertEquals(0, second.queued(), "已就绪且指纹未变 ⇒ 不重新导入");
    assertEquals(DocumentState.READY, reopened.status("ops").get(0).state());
  }

  /** 取一个与 {@code indexService} 共用 store 的 watcher（用例里换了服务实例时用）。 */
  private KnowledgeWatcher watcherWith(KnowledgeIndexService service) {
    return new KnowledgeWatcher(root, service, Runnable::run);
  }

  /** 只收任务、不自动执行：模拟后台段尚未跑完（或进程已被杀）。 */
  private static final class ControllableExecutor implements java.util.concurrent.Executor {
    private final java.util.List<Runnable> tasks = new java.util.ArrayList<>();

    @Override
    public synchronized void execute(Runnable command) {
      tasks.add(command);
    }

    synchronized int queued() {
      return tasks.size();
    }

    void runAll() {
      while (true) {
        Runnable next;
        synchronized (this) {
          if (tasks.isEmpty()) {
            return;
          }
          next = tasks.remove(0);
        }
        next.run();
      }
    }
  }
}
