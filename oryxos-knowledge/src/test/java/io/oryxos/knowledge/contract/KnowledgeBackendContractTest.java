package io.oryxos.knowledge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.embedding.TextEmbedder;
import io.oryxos.core.knowledge.KnowledgeImportException;
import io.oryxos.core.knowledge.KnowledgeManifest;
import io.oryxos.core.knowledge.model.DocumentState;
import io.oryxos.core.knowledge.model.DocumentStatus;
import io.oryxos.core.knowledge.model.KnowledgeHit;
import io.oryxos.core.knowledge.model.KnowledgeQuery;
import io.oryxos.knowledge.LocalKnowledgeBackend;
import io.oryxos.knowledge.index.KnowledgeIndexService;
import io.oryxos.knowledge.store.InMemoryChunkStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 行为契约测试骨架（T022，contracts/knowledge-spi.md §2）：钉死出处强制、范围限定、降级、 一致性拒绝、mock 确定性、能力诚实、双缓冲。impl-C
 * 阶段挂「仅检索」远程桩后参数化覆盖（SC-011）。
 */
class KnowledgeBackendContractTest {

  @TempDir Path root;

  private InMemoryChunkStore store;
  private KnowledgeIndexService indexService;
  private LocalKnowledgeBackend backend;
  private final AtomicReference<Supplier<TextEmbedder>> embedderRef = new AtomicReference<>();
  private final List<Runnable> backgroundTasks = new ArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    embedderRef.set(() -> new TestEmbedder("test/v1"));
    store = new InMemoryChunkStore();
    Supplier<TextEmbedder> supplier = () -> embedderRef.get().get();
    indexService = new KnowledgeIndexService(root, store, supplier, backgroundTasks::add);
    backend = new LocalKnowledgeBackend(root, store, indexService, supplier);
    createKb("ops", "运维手册");
    writeDoc("ops", "disk-alert.md", "# 磁盘告警处置\n\n先查 inode 占用，再清理过期日志。");
  }

  @Test
  void twoPhaseImportDrivesStateMachineAndCitationIsMandatory() {
    DocumentStatus pending = backend.importDocument("ops", "disk-alert.md");
    assertEquals(DocumentState.PENDING, pending.state(), "同步段登记 PENDING（Clarify-Q3）");

    runBackground();
    DocumentStatus ready = backend.status("ops").get(0);
    assertEquals(DocumentState.READY, ready.state());
    assertTrue(ready.chunkCount() > 0);

    List<KnowledgeHit> hits = retrieve("磁盘告警", 5, "ops");
    assertFalse(hits.isEmpty());
    KnowledgeHit top = hits.get(0);
    // 行为契约 1：出处完整（库名 + 相对路径 + 片段位置）且本地可跟读
    assertEquals("ops", top.citation().kbName());
    assertEquals("disk-alert.md", top.citation().relPath());
    assertEquals("1", top.citation().position());
    assertTrue(top.citation().readable());
    assertFalse(top.degraded());
  }

  @Test
  void retrievalIsDeterministicAcrossRepeatedCalls() {
    indexReady();
    writeDoc("ops", "faq.md", "# 常见问题\n\n磁盘满了先扩容还是先清理？");
    backend.importDocument("ops", "faq.md");
    runBackground();

    List<String> first =
        retrieve("磁盘", 5, "ops").stream().map(h -> h.citation().display()).toList();
    List<String> second =
        retrieve("磁盘", 5, "ops").stream().map(h -> h.citation().display()).toList();

    // 行为契约 6：确定性向量下同查询恒同排序（SC-004）
    assertEquals(first, second);
  }

  @Test
  void embedderOutageDegradesRetrievalToKeywordAndFailsImportExplicitly() {
    indexReady();
    embedderRef.set(
        () -> {
          throw new IllegalArgumentException(
              "未配置 embedding provider（knowledge.embedding.provider）");
        });

    // 行为契约 4a：检索降级为关键词并逐条标注
    List<KnowledgeHit> hits = retrieve("磁盘告警", 5, "ops");
    assertFalse(hits.isEmpty());
    assertTrue(hits.stream().allMatch(KnowledgeHit::degraded));
    assertTrue(String.valueOf(hits.get(0).payload().get("degraded_reason")).contains("关键词"));

    // 行为契约 4b：导入显式失败可重试，不静默丢弃
    writeDoc("ops", "new.md", "# 新文档\n\n内容");
    backend.importDocument("ops", "new.md");
    runBackground();
    DocumentStatus failed =
        backend.status("ops").stream().filter(s -> s.relPath().equals("new.md")).findFirst().get();
    assertEquals(DocumentState.FAILED, failed.state());
    assertTrue(failed.failureReason().contains("embedding"));
  }

  @Test
  void embeddingModelChangeRefusesMixedComparisonAndPromptsRebuild() {
    indexReady();
    embedderRef.set(() -> new TestEmbedder("test/v2"));

    List<KnowledgeHit> hits = retrieve("磁盘告警", 5, "ops");

    // 行为契约 5：不混比新旧向量、不静默错误排序；关键词路服务并提示重建（FR-014）
    assertFalse(hits.isEmpty());
    assertTrue(hits.stream().allMatch(KnowledgeHit::degraded));
    assertTrue(String.valueOf(hits.get(0).payload().get("degraded_reason")).contains("重建"));
  }

  @Test
  void importRejectsUnsupportedEmptyAndScannedAtEntry() throws IOException {
    Files.writeString(root.resolve("ops").resolve("report.docx"), "x");
    assertReadableImportError("ops", "report.docx", "不支持");

    Files.writeString(root.resolve("ops").resolve("empty.md"), " \n");
    assertReadableImportError("ops", "empty.md", "空文档");

    try (PDDocument scanned = new PDDocument()) {
      scanned.addPage(new PDPage());
      scanned.save(root.resolve("ops").resolve("scan.pdf").toFile());
    }
    assertReadableImportError("ops", "scan.pdf", "扫描件");

    // 入口即拒绝：不产生半完成状态（Edge Cases）
    assertTrue(backend.status("ops").isEmpty());
  }

  @Test
  void rebuildIsDoubleBufferedAndFailureKeepsOldGeneration() throws IOException {
    indexReady();
    assertFalse(retrieve("磁盘告警", 5, "ops").isEmpty());

    // 失败路径：目录里混入扫描件 → 重建整体失败，旧代不受影响（FR-024）
    try (PDDocument scanned = new PDDocument()) {
      scanned.addPage(new PDPage());
      scanned.save(root.resolve("ops").resolve("scan.pdf").toFile());
    }
    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> backend.rebuild("ops"));
    assertTrue(failure.getMessage().contains("旧索引不受影响"));
    assertFalse(retrieve("磁盘告警", 5, "ops").isEmpty(), "重建失败后旧索引照常服务");
    assertTrue(store.chunks("ops", 1).isEmpty(), "失败的新代已被丢弃");

    // 成功路径：换内容重建 → 新代生效、旧代清理
    Files.delete(root.resolve("ops").resolve("scan.pdf"));
    writeDoc("ops", "disk-alert.md", "# 全新处置手册\n\n关于网络故障的全新说明。");
    backend.rebuild("ops");
    assertFalse(retrieve("网络故障", 5, "ops").isEmpty());
    assertTrue(store.chunks("ops", 0).isEmpty(), "切换后旧代已清理");
  }

  @Test
  void multiKbRetrievalAggregatesToGlobalTopK() {
    indexReady();
    createKb("faq", "产品FAQ");
    writeDoc("faq", "disk.md", "# 磁盘常见问题\n\n磁盘告警阈值默认 85%。");
    backend.importDocument("faq", "disk.md");
    runBackground();

    List<KnowledgeHit> hits = retrieve("磁盘告警", 2, "ops", "faq");

    // 聚合全局 top-K：条数与库数无关（Clarify-Q2）；出处可区分来源库
    assertEquals(2, hits.size());
    assertTrue(hits.stream().allMatch(h -> !h.citation().kbName().isBlank()));
    assertTrue(hits.get(0).score() >= hits.get(1).score());
  }

  @Test
  void capabilitiesAreHonestAboutAdminPresence() {
    // 行为契约 7：能力声明与 admin() 有无一致（规避「契约谎言」）
    assertTrue(backend.capabilities().importDocs());
    assertTrue(backend.admin().isPresent());
    assertEquals("local", backend.name());
    assertFalse(backend.capabilities().rerank(), "v1 精排只留槽位不实现");
  }

  // ---- helpers ----

  private void indexReady() {
    backend.importDocument("ops", "disk-alert.md");
    runBackground();
  }

  private void runBackground() {
    List<Runnable> tasks = new ArrayList<>(backgroundTasks);
    backgroundTasks.clear();
    tasks.forEach(Runnable::run);
  }

  private List<KnowledgeHit> retrieve(String query, int topK, String... kbs) {
    return backend.retrieve(new KnowledgeQuery(query, topK, List.of(kbs)));
  }

  private void assertReadableImportError(String kb, String relPath, String keyword) {
    KnowledgeImportException rejected =
        assertThrows(KnowledgeImportException.class, () -> backend.importDocument(kb, relPath));
    assertTrue(rejected.getMessage().contains(keyword), "拒绝原因必须可读: " + rejected.getMessage());
  }

  private void createKb(String name, String description) {
    try {
      Path dir = Files.createDirectories(root.resolve(name));
      Files.writeString(
          dir.resolve(KnowledgeManifest.FILE),
          "---\nname: " + name + "\ndescription: " + description + "\n---\n");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void writeDoc(String kb, String relPath, String content) {
    try {
      Files.writeString(root.resolve(kb).resolve(relPath), content);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** 确定性测试向量器：字符直方图 + 归一化；modelId 可配以测一致性拒绝。 */
  private static final class TestEmbedder implements TextEmbedder {

    private static final int DIM = 16;
    private final String modelId;

    TestEmbedder(String modelId) {
      this.modelId = modelId;
    }

    @Override
    public float[] embed(String text) {
      float[] vector = new float[DIM];
      for (int i = 0; i < text.length(); i++) {
        vector[text.charAt(i) % DIM] += 1;
      }
      double norm = 0;
      for (float value : vector) {
        norm += (double) value * value;
      }
      float length = (float) Math.sqrt(norm);
      if (length > 0) {
        for (int i = 0; i < DIM; i++) {
          vector[i] /= length;
        }
      }
      return vector;
    }

    @Override
    public String modelId() {
      return modelId;
    }

    @Override
    public int dimensions() {
      return DIM;
    }
  }
}
