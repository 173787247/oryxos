package io.oryxos.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.knowledge.model.DocumentState;
import io.oryxos.knowledge.store.ChunkStore.ChunkRecord;
import io.oryxos.knowledge.store.ChunkStore.DocumentRecord;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ChunkStore 契约：同一套用例驱动内存档与默认生产档（JPA，SQLite 与 PostgreSQL 共用一条代码路径）。
 *
 * <p>顺序是本契约的显式输出而不是实现细节：{@code LocalKnowledgeBackend} 的向量路与关键词路都在 store 返回的列表上做稳定排序， {@code
 * RetrievalPipeline.fuseByRank} 收分后同样稳定排序——得分相同时「哪几条进 top-K」由这里的顺序决定； {@code
 * KnowledgeIndexService.status()} 还把 {@code documents(...)} 的顺序原样透出到 API。
 *
 * <p>另有两处语义分叉，本契约不擅自改实现，只把它显式声明出来（见 {@link #unknownDocumentIdSemantics()}）： 未知文档 id 的处置（拒绝 vs
 * upsert）。
 */
abstract class ChunkStoreContractTest {

  private static final String KB = "contract";
  private static final String OTHER_KB = "other";
  private static final Instant INDEXED_AT = Instant.parse("2026-01-01T00:00:00Z");

  /** 每个用例一份干净实现：内存档每次新建，SQLite 档由子类在 {@code @BeforeEach} 里清库。 */
  protected ChunkStore store;

  @BeforeEach
  void openStore() {
    store = newStore();
  }

  /** 被测实现。 */
  protected abstract ChunkStore newStore();

  /**
   * 未知文档 id 的分叉声明（语义两可，需维护者定夺）：{@code true} = 拒绝（{@code IllegalStateException}）， {@code false} = 按该
   * id 落库（upsert）。
   */
  protected abstract boolean rejectsUnknownDocumentId();

  @Test
  @DisplayName("documents_按relPath升序_同库同代_跨库跨代不混入")
  void documentsAreOrderedByRelPath() {
    saveDocument(KB, "c.md", 1);
    saveDocument(KB, "a.md", 1);
    saveDocument(KB, "b.md", 1);
    saveDocument(KB, "a.md", 2);
    saveDocument(OTHER_KB, "a.md", 1);

    assertEquals(
        List.of("a.md", "b.md", "c.md"),
        relPaths(store.documents(KB, 1)),
        "documents(...) 按 relPath 升序；落库次序与字母序相反，靠扫描或哈希的偶然次序蒙不对");
    assertEquals(1, store.documents(KB, 2).size(), "另一代不混入");
    assertEquals(1, store.documents(OTHER_KB, 1).size(), "另一库不混入");
  }

  /**
   * 注：id 按落库顺序分配，内存档去掉 {@code sorted(comparingLong(id))} 后返回次序并不变（哈希迭代恰好也是升序）， 所以这条钉的是 SQL
   * 档——它此前只有扫描顺序可依赖，本契约用仓储的 ORDER BY 把它固定住。
   */
  @Test
  @DisplayName("chunks_保留落库次序_同库同代_跨库跨代不混入")
  void chunksKeepInsertionOrder() {
    long documentId = saveDocument(KB, "a.md", 1).id();
    long otherDocumentId = saveDocument(OTHER_KB, "a.md", 1).id();
    store.saveChunks(
        List.of(chunk(documentId, KB, 1, 1, "第一段"), chunk(documentId, KB, 1, 2, "第二段")));
    store.saveChunks(List.of(chunk(documentId, KB, 2, 1, "另一代")));
    store.saveChunks(List.of(chunk(otherDocumentId, OTHER_KB, 1, 1, "另一库")));

    List<ChunkRecord> stored = store.chunks(KB, 1);
    assertEquals(List.of("第一段", "第二段"), contents(stored));
    assertAscendingIds(stored);
  }

  @Test
  @DisplayName("saveDocument_已知id_原地更新不新增一行")
  void saveDocumentWithKnownIdUpdatesInPlace() {
    DocumentRecord saved = saveDocument(KB, "a.md", 1);
    assertNotNull(saved.id(), "首次保存必须回填 id");

    store.saveDocument(saved.withState(DocumentState.FAILED, "解析失败"));

    assertEquals(1, store.documents(KB, 1).size(), "带 id 的保存是更新，不得插入第二行");
    DocumentRecord reread = store.findDocument(KB, "a.md", 1).orElseThrow();
    assertEquals(saved.id(), reread.id(), "更新不得换 id");
    assertEquals(DocumentState.FAILED, reread.state());
    assertEquals("解析失败", reread.failureReason(), "失败原因要能在读回时看到");
    assertEquals(1, reread.generation());
  }

  @Test
  @DisplayName("saveDocument_未知id_按各自声明处置（分叉待维护者定夺）")
  void unknownDocumentIdSemantics() {
    DocumentRecord orphan = document(999_999L, KB, "orphan.md", 1);

    if (rejectsUnknownDocumentId()) {
      assertThrows(
          IllegalStateException.class,
          () -> store.saveDocument(orphan),
          "生产档把带 id 的保存当更新：库里没有该行时必须报错，不能静默插一行谁也没登记的文档");
      assertTrue(store.documents(KB, 1).isEmpty(), "报错之后不得留下任何行");
      return;
    }
    assertEquals(999_999L, store.saveDocument(orphan).id(), "内存档把未知 id 当 upsert，按该 id 落库");
    assertEquals(List.of("orphan.md"), relPaths(store.documents(KB, 1)));
  }

  @Test
  @DisplayName("deleteGenerationsBelow_只删严格更旧的代号_边界代号保留_不越库")
  void deleteGenerationsBelowIsStrictlyBelow() {
    for (long generation : List.of(0L, 1L, 2L)) {
      long documentId = saveDocument(KB, "gen" + generation + ".md", generation).id();
      store.saveChunks(List.of(chunk(documentId, KB, generation, 1, "gen" + generation)));
    }
    long otherDocumentId = saveDocument(OTHER_KB, "gen0.md", 0).id();
    store.saveChunks(List.of(chunk(otherDocumentId, OTHER_KB, 0, 1, "另一库的旧代")));

    store.deleteGenerationsBelow(KB, 1);

    assertTrue(store.documents(KB, 0).isEmpty(), "代号 0 严格小于 1，文档必须清掉");
    assertTrue(store.chunks(KB, 0).isEmpty(), "旧代片段必须一并清掉，否则检索读到无主片段");
    assertEquals(1, store.documents(KB, 1).size(), "边界是「<」：等于 1 的代号必须留下");
    assertEquals(1, store.chunks(KB, 1).size());
    assertEquals(1, store.documents(KB, 2).size(), "更新的代号不受影响");
    assertEquals(1, store.chunks(KB, 2).size());
    assertEquals(1, store.documents(OTHER_KB, 0).size(), "清理只作用于指定库");
    assertEquals(1, store.chunks(OTHER_KB, 0).size());
  }

  @Test
  @DisplayName("向量往返_null保持null_非空逐位相等")
  void vectorRoundTripKeepsNullDistinctFromEmpty() {
    long documentId = saveDocument(KB, "a.md", 1).id();
    float[] vector = {0.5f, -1.25f, 3.0f, 0.0f};
    store.saveChunks(
        List.of(
            new ChunkRecord(null, documentId, KB, 1, null, "有向量", vector, "test/v1", 1),
            new ChunkRecord(null, documentId, KB, 2, 7, "无向量", null, null, 1)));

    List<ChunkRecord> stored = store.chunks(KB, 1);
    assertEquals(2, stored.size());
    ChunkRecord withVector = stored.get(0);
    ChunkRecord withoutVector = stored.get(1);
    assertArrayEquals(vector, withVector.embedding(), "向量落库再读出必须逐位相等（float32 小端 BLOB）");
    assertEquals("test/v1", withVector.embeddingModel());
    assertNull(withVector.pageNo(), "非 PDF 片段没有页码");
    assertNull(withoutVector.embedding(), "null 表示「无向量」，不能变成零长数组");
    assertNull(withoutVector.embeddingModel());
    assertNotNull(withoutVector.pageNo());
    assertEquals(7, withoutVector.pageNo().intValue(), "页码往返（PDF 出处用）");
  }

  /**
   * 传入 id 一律忽略：生产档由 DB 自增分配、内存档由内部计数器分配，采纳调用方给的值还会与实现自己的计数器撞车， 把别人的行静默覆盖掉。读回的 id 以 {@code
   * chunks(...)} 为准。
   */
  @Test
  @DisplayName("saveChunks_id由实现分配_调用方传入的id被忽略_不覆盖已有行")
  void saveChunksAssignsIdsAndIgnoresCallerId() {
    long documentId = saveDocument(KB, "a.md", 1).id();
    store.saveChunks(List.of(chunk(documentId, KB, 1, 1, "先落库的片段")));
    ChunkRecord first = store.chunks(KB, 1).get(0);
    assertNotNull(first.id(), "读回的片段必须带实现分配的 id");

    ChunkRecord withCallerId =
        new ChunkRecord(first.id(), documentId, KB, 2, null, "调用方声称的 id", null, null, 1);
    store.saveChunks(List.of(withCallerId));

    List<ChunkRecord> stored = store.chunks(KB, 1);
    assertEquals(2, stored.size(), "传入 id 被忽略 ⇒ 落成新行；采纳它会把已有行覆盖掉");
    assertEquals(List.of("先落库的片段", "调用方声称的 id"), contents(stored), "已有行不得被覆盖");
    assertNotNull(stored.get(1).id(), "读回的片段必须带实现分配的 id");
    assertNotEquals(first.id(), stored.get(1).id(), "回读的 id 是实现分配的，不是调用方传的那个");
  }

  private DocumentRecord saveDocument(String kbName, String relPath, long generation) {
    return store.saveDocument(document(null, kbName, relPath, generation));
  }

  private static DocumentRecord document(Long id, String kbName, String relPath, long generation) {
    return new DocumentRecord(
        id,
        kbName,
        relPath,
        "sha-" + relPath,
        DocumentState.READY,
        null,
        0,
        generation,
        INDEXED_AT);
  }

  private static ChunkRecord chunk(
      long documentId, String kbName, long generation, int seq, String content) {
    return new ChunkRecord(null, documentId, kbName, seq, null, content, null, null, generation);
  }

  private static List<String> relPaths(List<DocumentRecord> documents) {
    return documents.stream().map(DocumentRecord::relPath).toList();
  }

  private static List<String> contents(List<ChunkRecord> chunks) {
    return chunks.stream().map(ChunkRecord::content).toList();
  }

  private static void assertAscendingIds(List<ChunkRecord> chunks) {
    Long previous = null;
    for (ChunkRecord chunk : chunks) {
      assertNotNull(chunk.id(), "读回的片段必须带 id");
      if (previous != null) {
        assertTrue(chunk.id() > previous, "片段按 id 升序返回，即保留落库次序");
      }
      previous = chunk.id();
    }
  }
}
