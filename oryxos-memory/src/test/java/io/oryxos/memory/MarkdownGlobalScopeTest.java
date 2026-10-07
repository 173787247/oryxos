package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.agent.ToolExecutionContext;
import io.oryxos.core.embedding.TextEmbedder;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.storage.MemoryEntry;
import io.oryxos.storage.MemoryVectorEntity;
import io.oryxos.storage.MemoryVectorRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 全局作用域在 markdown 档只有一个名字、一份文件（015 FR-014）。无 Agent 上下文与索引里的全局占位名 {@link MemoryEntry#GLOBAL_AGENT}
 * 指的是同一个作用域：写入落在 `.oryxos/memory/MEMORY.md`，启动对账也读这一份。
 *
 * <p>若两者解析到不同文件（占位名落到 `agents/&lt;__global__&gt;/MEMORY.md`，那份通常是空的），对账会把真实全局条目的索引行
 * 当孤儿删掉——语义路此后恒空；反过来，全局文件里的存量条目（升级上来的机器）永远不进索引。
 */
class MarkdownGlobalScopeTest {

  private static final String AGENT = "ops-agent";
  private static final String GLOBAL_FILE = "memory/MEMORY.md";
  private static final String SENTINEL_FILE = "agents/" + MemoryEntry.GLOBAL_AGENT + "/MEMORY.md";
  private static final String SEMANTIC_ENTRY = "发布流程在灰度环节踩雷，回滚后改为分批放量";
  private static final String QUERY = "上次那个部署的坑怎么处理的";
  private static final String[] FILLERS = {"例行巡检无异常 A", "例行巡检无异常 B", "例行巡检无异常 C", "例行巡检无异常 D"};
  private static final Executor DIRECT = Runnable::run;

  @TempDir Path root;

  private List<MemoryVectorEntity> rows;
  private MarkdownMemoryStore store;
  private MemoryServiceImpl service;

  /** 内容感知的确定性 embedder（维度 2）：只有语义路能召回 SEMANTIC_ENTRY。 */
  private static final TextEmbedder EMBEDDER =
      new TextEmbedder() {
        @Override
        public float[] embed(String text) {
          if (text != null && text.contains("灰度环节踩雷")) {
            return new float[] {0.99f, 0.14f};
          }
          if (text != null && text.contains("部署的坑")) {
            return new float[] {1f, 0f};
          }
          return new float[] {0f, 1f};
        }

        @Override
        public String modelId() {
          return "qwen/text-embedding-v3";
        }

        @Override
        public int dimensions() {
          return 2;
        }
      };

  /** 背靠内存 List 的有状态 mock 仓库（与 MemoryVectorIndexTest 同法）。 */
  private static MemoryVectorRepository fakeRepo(List<MemoryVectorEntity> data) {
    MemoryVectorRepository repo = mock(MemoryVectorRepository.class);
    when(repo.save(any()))
        .thenAnswer(
            inv -> {
              MemoryVectorEntity e = inv.getArgument(0);
              data.removeIf(
                  row ->
                      row.getAgentName().equals(e.getAgentName())
                          && row.getEntryHash().equals(e.getEntryHash()));
              data.add(e);
              return e;
            });
    when(repo.findByAgentName(anyString()))
        .thenAnswer(
            inv -> {
              String agent = inv.getArgument(0);
              return data.stream().filter(row -> row.getAgentName().equals(agent)).toList();
            });
    when(repo.findByAgentNameAndEntryHash(anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String agent = inv.getArgument(0);
              String hash = inv.getArgument(1);
              return data.stream()
                  .filter(
                      row -> row.getAgentName().equals(agent) && row.getEntryHash().equals(hash))
                  .findFirst();
            });
    org.mockito.Mockito.doAnswer(
            inv -> {
              String agent = inv.getArgument(0);
              Collection<?> hashes = inv.getArgument(1);
              data.removeIf(
                  row -> row.getAgentName().equals(agent) && hashes.contains(row.getEntryHash()));
              return null;
            })
        .when(repo)
        .deleteByAgentNameAndEntryHashIn(anyString(), any());
    org.mockito.Mockito.doAnswer(
            inv -> {
              String model = inv.getArgument(0);
              data.removeIf(row -> !model.equals(row.getEmbeddingModel()));
              return null;
            })
        .when(repo)
        .deleteByEmbeddingModelNot(anyString());
    return repo;
  }

  @BeforeEach
  void setUp() {
    rows = new ArrayList<>();
    store = new MarkdownMemoryStore(root);
    MemoryVectorRepository repo = fakeRepo(rows);
    service =
        new MemoryServiceImpl(
            store,
            new MemoryRecallEngine(repo, EMBEDDER, new double[] {1, 1, 1}, 3),
            new MemoryVectorIndex(repo, EMBEDDER, DIRECT));
  }

  @AfterEach
  void clearContext() {
    ToolExecutionContext.clear();
  }

  /** 语义路唯一可命中的那条：关键词路不含该措辞、时间新近路因 topK=3 把它挤在外面。 */
  private boolean semanticRecallHitsGlobalEntry() {
    assertTrue(store.recallByKeyword(QUERY).isEmpty(), "该问句在关键词路上无命中，命中只能来自语义路");
    return service.recall(QUERY).stream().anyMatch(line -> line.contains(SEMANTIC_ENTRY));
  }

  private void seedGlobalCorpus() {
    service.remember(SEMANTIC_ENTRY, MemoryScope.ARCHIVAL); // 最先写 → 时间新近路排最后
    for (String filler : FILLERS) {
      service.remember(filler, MemoryScope.ARCHIVAL);
    }
  }

  @Test
  @DisplayName("无上下文与全局占位名指向同一份 MEMORY.md_不产生第二份全局文件")
  void globalSentinelAndNoContextAddressTheSameMemoryFile() {
    ToolExecutionContext.clear();
    store.append("无上下文的全局条目", MemoryScope.ARCHIVAL);

    ToolExecutionContext.setAgentName(MemoryEntry.GLOBAL_AGENT);
    assertEquals(1, store.recallByKeyword("无上下文的全局条目").size(), "带全局占位名必须读到无上下文写的那份文件");
    store.append("占位名写入的条目", MemoryScope.CORE);

    ToolExecutionContext.clear();
    assertTrue(store.load().contains("占位名写入的条目"), "无上下文必须读到带全局占位名写的那份文件");
    assertFalse(Files.exists(root.resolve(SENTINEL_FILE)), "全局作用域不得解析出第二份文件 " + SENTINEL_FILE);
  }

  @Test
  @DisplayName("启动对账保留全局索引行_对账后语义路仍能召回（FR-007 幂等）")
  void startupReconcileKeepsGlobalIndexRowsAndSemanticRecall() {
    ToolExecutionContext.clear();
    seedGlobalCorpus();
    assertEquals(5, rows.size(), "全局归档条目写入即入索引");
    assertTrue(Files.exists(root.resolve(GLOBAL_FILE)), "全局条目落在 memory/MEMORY.md");
    assertTrue(semanticRecallHitsGlobalEntry(), "对账前语义路可用（对照组）");

    service.reconcileIndex(MemoryEntry.GLOBAL_AGENT);

    assertEquals(5, rows.size(), "本体仍在的全局条目不得被对账当孤儿删掉");
    assertTrue(semanticRecallHitsGlobalEntry(), "对账后语义路仍须召回措辞不同的那条全局记忆");

    service.reconcileIndex(MemoryEntry.GLOBAL_AGENT);
    assertEquals(5, rows.size(), "对账幂等：第二次不改变行数");
  }

  @Test
  @DisplayName("启动对账索引全局文件的存量条目（升级上来的机器）")
  void startupReconcileIndexesTheLegacyGlobalFile() {
    Path legacy = root.resolve(GLOBAL_FILE);
    StringBuilder content = new StringBuilder("## 核心记忆\n\n## 归档记忆\n");
    content.append("- [2026-08-20 10:00:00] ").append(SEMANTIC_ENTRY).append('\n');
    for (String filler : FILLERS) {
      content.append("- [2026-08-20 10:01:00] ").append(filler).append('\n');
    }
    try {
      Files.createDirectories(legacy.getParent());
      Files.writeString(legacy, content.toString());
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }

    ToolExecutionContext.clear();
    assertEquals(0, rows.size(), "对账前索引是空的");
    service.reconcileIndex(MemoryEntry.GLOBAL_AGENT);

    assertEquals(5, rows.size(), "全局文件里的存量条目必须随对账入索引");
    assertTrue(semanticRecallHitsGlobalEntry(), "存量全局条目必须进语义路");
  }

  @Test
  @DisplayName("反向用例：真 Agent 仍写自己的文件_按 Agent 对账不碰全局行")
  void perAgentScopeKeepsItsOwnFileAndDoesNotTouchGlobalRows() {
    ToolExecutionContext.clear();
    service.remember(SEMANTIC_ENTRY, MemoryScope.ARCHIVAL);

    ToolExecutionContext.setAgentName(AGENT);
    service.remember("这个 Agent 自己的事实", MemoryScope.ARCHIVAL);
    assertTrue(
        Files.exists(root.resolve("agents").resolve(AGENT).resolve("MEMORY.md")),
        "真 Agent 仍写自己的 MEMORY.md");

    ToolExecutionContext.clear();
    assertFalse(store.load().contains("这个 Agent 自己的事实"), "Agent 条目不得落进全局文件");
    assertTrue(store.load().contains(SEMANTIC_ENTRY), "全局文件仍只有全局条目");
    assertEquals(2, rows.size(), "全局与 Agent 各一条，写入即入索引");

    service.reconcileIndex(AGENT);

    assertEquals(2, rows.size(), "按 Agent 对账不得删掉全局行");
    assertTrue(
        rows.stream().anyMatch(row -> row.getAgentName().equals(MemoryEntry.GLOBAL_AGENT)),
        "全局行仍在");
    assertTrue(rows.stream().anyMatch(row -> row.getAgentName().equals(AGENT)), "Agent 行仍在");
    assertFalse(Files.exists(root.resolve(SENTINEL_FILE)), "真 Agent 路径不受影响，也不该出现占位名文件");
  }
}
