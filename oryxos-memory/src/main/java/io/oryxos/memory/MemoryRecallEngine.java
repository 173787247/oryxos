package io.oryxos.memory;

import io.oryxos.core.embedding.TextEmbedder;
import io.oryxos.core.embedding.VectorCodec;
import io.oryxos.core.memory.MemoryEntryView;
import io.oryxos.core.retrieval.RetrievalPipeline;
import io.oryxos.storage.MemoryVectorRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 记忆三路召回引擎（015 FR-001/003）：语义（memory_vectors 余弦）+ 关键词（store 统一大小写行为）+ 时间新近，三路候选按 entry_hash 对齐后做加权
 * RRF 融合（复用 core RetrievalPipeline）。
 *
 * <p>模式边界：未配置向量化（embedder=null）时 recall 直通关键词旧行为——不融合、不截断、不标注 （FR-013 字节级兼容）；已配置但语义路本次失败时降级为关键词 +
 * 时间两路并在结果尾行标注（FR-003）。 关键词与时间两路读记忆本体，天然覆盖全部条目——索引落后只缩小语义路覆盖面，不产生召回黑洞。
 */
public class MemoryRecallEngine {

  /** 降级标注（contracts §4）：仅已配置向量化且本次降级时追加为结果尾行。 */
  public static final String DEGRADE_NOTICE = "（语义检索暂不可用，已按关键词与时间返回）";

  private static final Logger log = LoggerFactory.getLogger(MemoryRecallEngine.class);

  private final MemoryVectorRepository vectorRepository;
  private final TextEmbedder embedder;
  private final double[] weights; // [semantic, keyword, recency]
  private final int topK;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository/embedder 为装配注入的共享协作者，构造注入存同一引用正是意图")
  public MemoryRecallEngine(
      MemoryVectorRepository vectorRepository, TextEmbedder embedder, double[] weights, int topK) {
    this.vectorRepository = vectorRepository;
    this.embedder = embedder;
    this.weights = weights.clone();
    this.topK = topK;
  }

  /** 三路召回；返回条目原文行（融合序），降级时尾行为 {@link #DEGRADE_NOTICE}。 */
  public List<String> recall(LongTermMemoryStore store, String agentName, String keyword) {
    List<String> keywordLines = store.recallByKeyword(keyword);
    if (embedder == null) {
      return keywordLines; // 未配置：旧行为逐字节保持（除大小写统一，由 store 层完成）
    }

    List<MemoryEntryView> archival = store.archivalEntries();
    Map<String, Long> idByHash = new LinkedHashMap<>();
    Map<Long, String> contentById = new HashMap<>();
    List<MemoryEntryView> uniqueEntries = new ArrayList<>();
    for (MemoryEntryView entry : archival) {
      String hash = MemoryVectorIndex.entryHash(agentName, entry.content());
      if (!idByHash.containsKey(hash)) {
        long id = idByHash.size() + 1L;
        idByHash.put(hash, id);
        contentById.put(id, entry.content());
        uniqueEntries.add(entry);
      }
    }

    List<RetrievalPipeline.Candidate> keywordRoute =
        keywordRoute(keywordLines, agentName, idByHash);
    List<RetrievalPipeline.Candidate> recencyRoute =
        recencyRoute(uniqueEntries, agentName, idByHash);

    boolean degraded = false;
    List<RetrievalPipeline.Candidate> semanticRoute = List.of();
    try {
      semanticRoute = semanticRoute(agentName, keyword, idByHash);
    } catch (RuntimeException e) {
      degraded = true;
      log.warn("语义路本次不可用，降级为关键词 + 时间两路: {}", e.getMessage());
    }

    List<String> lines = new ArrayList<>();
    for (RetrievalPipeline.Fused fused :
        RetrievalPipeline.fuseByRank(topK, weights, semanticRoute, keywordRoute, recencyRoute)) {
      lines.add(contentById.get(fused.id()));
    }
    if (degraded) {
      lines.add(DEGRADE_NOTICE);
    }
    return lines;
  }

  /** 关键词路：LIKE/contains 无相关性分，路内按新近排（store 返回写入序，反转即新近在前）。 */
  private static List<RetrievalPipeline.Candidate> keywordRoute(
      List<String> keywordLines, String agentName, Map<String, Long> idByHash) {
    List<RetrievalPipeline.Candidate> route = new ArrayList<>();
    for (String line : keywordLines.reversed()) {
      Long id = idByHash.get(MemoryVectorIndex.entryHash(agentName, line));
      if (id != null) {
        route.add(new RetrievalPipeline.Candidate(id, 0));
      }
    }
    return route;
  }

  /** 时间新近路：全部本体条目按时间倒序（无时间视为最旧；同时间后写入的在前）。 */
  private static List<RetrievalPipeline.Candidate> recencyRoute(
      List<MemoryEntryView> uniqueEntries, String agentName, Map<String, Long> idByHash) {
    record Positioned(MemoryEntryView entry, int position) {}
    List<Positioned> ordered = new ArrayList<>(uniqueEntries.size());
    for (int i = 0; i < uniqueEntries.size(); i++) {
      ordered.add(new Positioned(uniqueEntries.get(i), i));
    }
    ordered.sort(
        Comparator.comparing(
                (Positioned p) -> p.entry().time() == null ? Instant.MIN : p.entry().time())
            .thenComparingInt(Positioned::position)
            .reversed());
    List<RetrievalPipeline.Candidate> route = new ArrayList<>(ordered.size());
    for (Positioned p : ordered) {
      route.add(
          new RetrievalPipeline.Candidate(
              idByHash.get(MemoryVectorIndex.entryHash(agentName, p.entry().content())), 0));
    }
    return route;
  }

  /** 语义路：只认当前模型、维度一致、且本体仍存在的索引行（孤儿行不产生幽灵结果）。 */
  private List<RetrievalPipeline.Candidate> semanticRoute(
      String agentName, String keyword, Map<String, Long> idByHash) {
    float[] query = embedder.embed(keyword);
    int queryDim = query.length;
    return vectorRepository.findByAgentName(agentName).stream()
        .filter(
            row ->
                embedder.modelId().equals(row.getEmbeddingModel())
                    && row.getDim() == queryDim
                    && idByHash.containsKey(row.getEntryHash()))
        .map(
            row ->
                new RetrievalPipeline.Candidate(
                    idByHash.get(row.getEntryHash()),
                    RetrievalPipeline.cosine(query, VectorCodec.decode(row.getEmbedding()))))
        .sorted(Comparator.comparingDouble(RetrievalPipeline.Candidate::score).reversed())
        .toList();
  }
}
