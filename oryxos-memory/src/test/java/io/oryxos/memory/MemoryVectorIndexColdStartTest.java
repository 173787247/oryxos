package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.embedding.TextEmbedder;
import io.oryxos.core.memory.MemoryEntryView;
import io.oryxos.core.memory.MemoryRecallCapability;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.storage.MemoryVectorEntity;
import io.oryxos.storage.MemoryVectorRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 015 FR-007 的「维度未知」一半：embedder 是惰性的（ProviderEmbeddingModelFactory 只在首次真实调用成功后才确定维度），
 * 而启动对账发生在任何真实调用之前——那一刻 `dimensions()` 报 0。0 是「未知」，不是「维度变了」：当成值用就会在每次进程 重启把「本来就是当前模型 +
 * 当前维度」的行全判陈旧、删光并全量重嵌入（每条记忆一次 embedding，重建期间语义路为空）。
 *
 * <p>反方向同样要钉死：维度真的变了（modelId 不变）时仍然必须判陈旧并重建（#859 / FR-007），所以「未知」不能简化成 「永不判陈旧」——对账要先确认维度，见 {@link
 * MemoryVectorIndex#reconcile}。
 */
class MemoryVectorIndexColdStartTest {

  private static final String AGENT = "ops-agent";

  /** 同一 provider/model：维度变了或还没取到时，modelId 都不会变。 */
  private static final String MODEL = "qwen/text-embedding-v3";

  private static final String SEMANTIC_ENTRY = "发布流程在灰度环节踩雷，回滚后改为分批放量";
  private static final String QUERY = "上次那个部署的坑怎么处理的";
  private static final String[] OTHERS = {"例行巡检无异常 A", "例行巡检无异常 B", "例行巡检无异常 C"};
  private static final Executor DIRECT = Runnable::run;

  /**
   * 惰性 embedder：与 {@code ProviderEmbeddingModelFactory.SpringAiTextEmbedder} 同形状——`dimensions()`
   * 在首次 真实调用成功之前报 0，之后报真实维度；每次 embed 的文本都记下来，好数「重嵌了几条」。
   */
  private static final class LazyDimEmbedder implements TextEmbedder {
    private final int dim;
    private final List<String> embedded = new ArrayList<>();

    LazyDimEmbedder(int dim) {
      this.dim = dim;
    }

    List<String> embedded() {
      return List.copyOf(embedded);
    }

    @Override
    public float[] embed(String text) {
      embedded.add(text);
      float[] vector = new float[dim];
      if (text != null && text.contains("灰度环节踩雷")) {
        vector[0] = 0.99f;
        if (dim > 1) {
          vector[1] = 0.14f;
        }
      } else if (text != null && text.contains("部署的坑")) {
        vector[0] = 1f;
      } else if (dim > 1) {
        vector[1] = 1f;
      } else {
        vector[0] = 1f;
      }
      return vector;
    }

    @Override
    public String modelId() {
      return MODEL;
    }

    @Override
    public int dimensions() {
      return embedded.isEmpty() ? 0 : dim;
    }
  }

  /** 归档条目背靠 List 的假后端（HYBRID_BUILTIN 档形状）。 */
  private static final class FakeStore implements LongTermMemoryStore {
    private final List<MemoryEntryView> archival = new ArrayList<>();

    FakeStore add(String content, Instant time) {
      archival.add(new MemoryEntryView(content, time));
      return this;
    }

    @Override
    public MemoryEntryView append(String content, MemoryScope scope) {
      MemoryEntryView view = new MemoryEntryView(content, Instant.now());
      archival.add(view);
      return view;
    }

    @Override
    public String load() {
      return "";
    }

    @Override
    public List<String> recallByKeyword(String keyword) {
      String needle = keyword.toLowerCase(java.util.Locale.ROOT);
      return archival.stream()
          .map(MemoryEntryView::content)
          .filter(content -> content.toLowerCase(java.util.Locale.ROOT).contains(needle))
          .toList();
    }

    @Override
    public MemoryRecallCapability capabilities() {
      return MemoryRecallCapability.HYBRID_BUILTIN;
    }

    @Override
    public List<MemoryEntryView> archivalEntries() {
      return List.copyOf(archival);
    }
  }

  private static FakeStore store() {
    Instant base = Instant.parse("2026-08-20T10:00:00Z");
    return new FakeStore()
        .add(SEMANTIC_ENTRY, base)
        .add(OTHERS[0], base.plusSeconds(60))
        .add(OTHERS[1], base.plusSeconds(120))
        .add(OTHERS[2], base.plusSeconds(180));
  }

  /** 背靠内存 List 的有状态 mock 仓库（含模型与条目两级删除）。 */
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

  private static List<Integer> dims(List<MemoryVectorEntity> data) {
    return data.stream().map(MemoryVectorEntity::getDim).toList();
  }

  private static List<String> recall(
      List<MemoryVectorEntity> data, TextEmbedder embedder, LongTermMemoryStore store) {
    return new MemoryRecallEngine(fakeRepo(data), embedder, new double[] {1, 1, 1}, 3)
        .recall(store, AGENT, QUERY);
  }

  /** 第一次写入（dim=dim 的 embedder），返回索引里的四行。 */
  private static List<MemoryVectorEntity> indexedAt(int dim, FakeStore store) {
    List<MemoryVectorEntity> data = new ArrayList<>();
    MemoryVectorIndex writer =
        new MemoryVectorIndex(fakeRepo(data), new LazyDimEmbedder(dim), DIRECT);
    for (MemoryEntryView entry : store.archivalEntries()) {
      writer.enqueue(AGENT, entry);
    }
    assertEquals(List.of(dim, dim, dim, dim), dims(data), "前置：四条都已按 " + dim + " 维索引");
    return data;
  }

  @Test
  @DisplayName("冷进程（惰性 embedder 尚未真实调用）_维度本来就对的行不得判陈旧全表重建")
  void reconcileKeepsCurrentRowsWhenTheEmbedderDoesNotKnowItsDimensionYet() {
    FakeStore store = store();
    List<MemoryVectorEntity> data = indexedAt(2, store);

    // 第 2 次启动：同一个 provider/model、维度没变，但新进程里的 embedder 还没被调用过
    LazyDimEmbedder cold = new LazyDimEmbedder(2);
    assertEquals(0, cold.dimensions(), "前置：冷 embedder 的 dimensions() 必须是 0");
    MemoryVectorIndex reader = new MemoryVectorIndex(fakeRepo(data), cold, DIRECT);
    reader.reconcile(AGENT, store.archivalEntries());

    List<String> contents = store.archivalEntries().stream().map(MemoryEntryView::content).toList();
    assertEquals(List.of(2, 2, 2, 2), dims(data), "维度没变：本来正确的行不得被清掉重建");
    assertEquals(4, data.size(), "行数不变");
    assertEquals(
        List.of(),
        cold.embedded().stream().filter(contents::contains).toList(),
        "维度没变：不得重新向量化任何归档条目（这正是每次重启的全量重嵌入）");
    assertEquals(1, cold.embedded().size(), "只为确认维度探测一次，实际调用 " + cold.embedded());

    reader.reconcile(AGENT, store.archivalEntries()); // 幂等（FR-007）：维度已确认，不再探测
    assertEquals(1, cold.embedded().size(), "对账再跑一遍不该有新的向量化调用");
    assertEquals(List.of(2, 2, 2, 2), dims(data));
  }

  @Test
  @DisplayName("冷进程 + 维度真的变了（modelId 不变）_仍然必须判陈旧重建_语义路仍召回")
  void reconcileStillRebuildsWhenTheDimensionChangedAndTheEmbedderIsCold() {
    FakeStore store = store();
    List<MemoryVectorEntity> data = indexedAt(2, store);

    // 第 2 次启动：modelId 不变、维度 2 → 3，且新进程里 embedder 还没被调用过
    LazyDimEmbedder cold = new LazyDimEmbedder(3);
    assertEquals(0, cold.dimensions(), "前置：冷 embedder 的 dimensions() 必须是 0");
    new MemoryVectorIndex(fakeRepo(data), cold, DIRECT).reconcile(AGENT, store.archivalEntries());

    assertEquals(List.of(3, 3, 3, 3), dims(data), "维度确实变了：旧行必须按当前维度重建");
    List<String> lines = recall(data, new LazyDimEmbedder(3), store);
    assertTrue(lines.contains(SEMANTIC_ENTRY), "维度变化后语义路必须召回措辞不同的那条记忆，实际 " + lines);
    assertFalse(lines.contains(MemoryRecallEngine.DEGRADE_NOTICE), "语义路可用，不该标注降级");
  }

  @Test
  @DisplayName("维度确认失败（端点不可用）_不判陈旧不删行，且每个进程只探测一次")
  void reconcileKeepsRowsWhenTheDimensionCannotBeEstablished() {
    FakeStore store = store();
    List<MemoryVectorEntity> data = indexedAt(2, store);

    List<String> probes = new ArrayList<>();
    TextEmbedder unavailable =
        new TextEmbedder() {
          @Override
          public float[] embed(String text) {
            probes.add(text);
            throw new IllegalStateException("embedding 服务不可用");
          }

          @Override
          public String modelId() {
            return MODEL;
          }

          @Override
          public int dimensions() {
            return 0;
          }
        };
    MemoryVectorIndex reader = new MemoryVectorIndex(fakeRepo(data), unavailable, DIRECT);

    reader.reconcile(AGENT, store.archivalEntries());
    assertEquals(List.of(2, 2, 2, 2), dims(data), "维度取不到时不得删行（删了就是这次回归的全表重建）");
    assertEquals(1, probes.size(), "探测一次就够，失败不重试");

    reader.reconcile(AGENT, store.archivalEntries());
    assertEquals(1, probes.size(), "同一进程内不再重复探测（端点不可用时不让每个作用域各付一次超时）");
    assertEquals(List.of(2, 2, 2, 2), dims(data));
  }

  @Test
  @DisplayName("冷进程_同条目重写入队时维度未知不重算（与对账同口径）")
  void enqueueDoesNotReEmbedWhenTheEmbedderDoesNotKnowItsDimensionYet() {
    FakeStore store = store();
    List<MemoryVectorEntity> data = indexedAt(2, store);
    MemoryEntryView entry = store.archivalEntries().get(0);

    LazyDimEmbedder cold = new LazyDimEmbedder(2);
    new MemoryVectorIndex(fakeRepo(data), cold, DIRECT).enqueue(AGENT, entry);

    assertEquals(List.of(2, 2, 2, 2), dims(data), "维度未知不是维度变了：同条目不得被重算");
    assertEquals(List.of(), cold.embedded(), "维度未知时不做无谓的重新向量化");
  }
}
