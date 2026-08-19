package io.oryxos.knowledge;

import io.oryxos.core.embedding.TextEmbedder;
import io.oryxos.core.fs.RealPathBoundary;
import io.oryxos.core.knowledge.KnowledgeAdmin;
import io.oryxos.core.knowledge.KnowledgeBackend;
import io.oryxos.core.knowledge.KnowledgeBackendRegistry;
import io.oryxos.core.knowledge.KnowledgeManifest;
import io.oryxos.core.knowledge.model.Citation;
import io.oryxos.core.knowledge.model.DocumentStatus;
import io.oryxos.core.knowledge.model.KnowledgeCapabilities;
import io.oryxos.core.knowledge.model.KnowledgeHit;
import io.oryxos.core.knowledge.model.KnowledgeQuery;
import io.oryxos.knowledge.index.KnowledgeIndexService;
import io.oryxos.knowledge.retrieve.RetrievalPipeline;
import io.oryxos.knowledge.store.ChunkStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 内置本地后端——契约的第一个插件（FR-015 缺省实现，零外部服务依赖）：文档在本机、索引在 SQLite、检索走「双路召回（向量余弦 ∥ 关键词）→ RRF 名次融合 → 精排槽位（v1
 * 空）」（FR-004）。 embedding 不可用或向量模型不一致时向量路关停、关键词路独立服务并逐条标注降级（FR-013/014）。
 */
public class LocalKnowledgeBackend implements KnowledgeBackend, KnowledgeAdmin {

  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]+");

  /** 每路召回候选量与 topK 的倍数：给融合层留足重排空间。 */
  private static final int ROUTE_FACTOR = 4;

  private final Path knowledgeRoot;
  private final ChunkStore store;
  private final KnowledgeIndexService indexService;
  private final Supplier<TextEmbedder> embedderSupplier;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "store/indexService 为装配层注入的共享单例，构造注入存同一引用正是意图（镜像既有 SuppressFBWarnings 模式）。")
  public LocalKnowledgeBackend(
      Path knowledgeRoot,
      ChunkStore store,
      KnowledgeIndexService indexService,
      Supplier<TextEmbedder> embedderSupplier) {
    this.knowledgeRoot = knowledgeRoot.toAbsolutePath().normalize();
    this.store = store;
    this.indexService = indexService;
    this.embedderSupplier = embedderSupplier;
  }

  @Override
  public String name() {
    return KnowledgeBackendRegistry.LOCAL;
  }

  @Override
  public KnowledgeCapabilities capabilities() {
    return KnowledgeCapabilities.localFull();
  }

  @Override
  public Optional<KnowledgeAdmin> admin() {
    return Optional.of(this);
  }

  @Override
  public List<KnowledgeHit> retrieve(KnowledgeQuery query) {
    List<KnowledgeHit> all = new ArrayList<>();
    for (String kbName : query.kbNames()) {
      all.addAll(retrieveOne(kbName, query.query(), query.topK()));
    }
    // 跨库融合取全局 top-K：结果条数与绑定库数无关（FR-020 / Clarify-Q2）
    return all.stream()
        .sorted(Comparator.comparingDouble(KnowledgeHit::score).reversed())
        .limit(query.topK())
        .toList();
  }

  private List<KnowledgeHit> retrieveOne(String kbName, String query, int topK) {
    long generation = indexService.activeGeneration(kbName);
    List<ChunkStore.ChunkRecord> chunks = store.chunks(kbName, generation);
    if (chunks.isEmpty()) {
      return List.of();
    }
    Map<Long, String> relPaths = new HashMap<>(chunks.size());
    store.documents(kbName, generation).forEach(doc -> relPaths.put(doc.id(), doc.relPath()));
    Map<Long, ChunkStore.ChunkRecord> byId = new HashMap<>(chunks.size());
    chunks.forEach(chunk -> byId.put(chunk.id(), chunk));

    String degradedReason = null;
    List<RetrievalPipeline.Candidate> vectorRoute = List.of();
    TextEmbedder embedder = tryEmbedder();
    if (embedder == null) {
      degradedReason = "向量化服务不可用，已降级为关键词检索";
    } else {
      String mismatch = modelMismatch(chunks, embedder.modelId());
      if (mismatch != null) {
        // FR-014：拒绝新旧向量混合比较，关键词路独立服务并提示重建，不静默返回错误排序
        degradedReason = mismatch;
      } else {
        vectorRoute = vectorRecall(chunks, embedder, query, topK * ROUTE_FACTOR);
      }
    }
    List<RetrievalPipeline.Candidate> keywordRoute =
        keywordRecall(chunks, query, topK * ROUTE_FACTOR);

    boolean degraded = degradedReason != null;
    Map<String, Object> payload = degraded ? Map.of("degraded_reason", degradedReason) : Map.of();
    List<KnowledgeHit> hits = new ArrayList<>();
    for (RetrievalPipeline.Fused fused :
        RetrievalPipeline.fuseByRank(topK, vectorRoute, keywordRoute)) {
      ChunkStore.ChunkRecord chunk = byId.get(fused.id());
      String relPath = relPaths.getOrDefault(chunk.documentId(), "");
      String position =
          chunk.pageNo() != null ? "page:" + chunk.pageNo() : String.valueOf(chunk.seq());
      hits.add(
          new KnowledgeHit(
              new Citation(kbName, relPath, position, true),
              chunk.content(),
              fused.score(),
              degraded,
              payload));
    }
    return hits;
  }

  private TextEmbedder tryEmbedder() {
    try {
      return embedderSupplier.get();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 存量向量模型与当前配置不一致时返回可读原因；一致返回 null（FR-014）。 */
  private static String modelMismatch(List<ChunkStore.ChunkRecord> chunks, String currentModel) {
    for (ChunkStore.ChunkRecord chunk : chunks) {
      if (chunk.embedding() != null
          && chunk.embeddingModel() != null
          && !chunk.embeddingModel().equals(currentModel)) {
        return "向量模型不一致（存量 "
            + chunk.embeddingModel()
            + " ≠ 当前 "
            + currentModel
            + "），请重建索引；已降级为关键词检索";
      }
    }
    return null;
  }

  private static List<RetrievalPipeline.Candidate> vectorRecall(
      List<ChunkStore.ChunkRecord> chunks, TextEmbedder embedder, String query, int limit) {
    float[] queryVector;
    try {
      queryVector = embedder.embed(query);
    } catch (RuntimeException e) {
      return List.of();
    }
    return chunks.stream()
        .filter(
            chunk -> chunk.embedding() != null && chunk.embedding().length == queryVector.length)
        .map(
            chunk ->
                new RetrievalPipeline.Candidate(
                    chunk.id(), RetrievalPipeline.cosine(queryVector, chunk.embedding())))
        .sorted(Comparator.comparingDouble(RetrievalPipeline.Candidate::score).reversed())
        .limit(limit)
        .toList();
  }

  /** 关键词路：空白分词的包含计数 + 整句包含加权；子串匹配天然覆盖中文（Edge Cases 中英混排）。 */
  private static List<RetrievalPipeline.Candidate> keywordRecall(
      List<ChunkStore.ChunkRecord> chunks, String query, int limit) {
    String whole = query.toLowerCase(Locale.ROOT).strip();
    String[] tokens = whole.split("\\s+");
    return chunks.stream()
        .map(
            chunk ->
                new RetrievalPipeline.Candidate(chunk.id(), keywordScore(chunk, whole, tokens)))
        .filter(candidate -> candidate.score() > 0)
        .sorted(Comparator.comparingDouble(RetrievalPipeline.Candidate::score).reversed())
        .limit(limit)
        .toList();
  }

  private static double keywordScore(ChunkStore.ChunkRecord chunk, String whole, String[] tokens) {
    String content = chunk.content().toLowerCase(Locale.ROOT);
    double score = 0;
    for (String token : tokens) {
      if (!token.isBlank() && content.contains(token)) {
        score += 1;
      }
    }
    if (content.contains(whole)) {
      score += 2;
    }
    return score;
  }

  // ---- KnowledgeAdmin（全能力声明，FR-006 契约诚实）----

  @Override
  public void createBase(String name, String description) {
    if (name == null || !SAFE_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("非法知识库名（只允许字母/数字/下划线/连字符）: " + name);
    }
    Path dir = knowledgeRoot.resolve(name);
    if (Files.exists(dir)) {
      throw new IllegalArgumentException("知识库已存在: " + name);
    }
    String desc = description == null ? "" : description.replace('\r', ' ').replace('\n', ' ');
    try {
      Files.createDirectories(dir);
      Files.writeString(
          dir.resolve(KnowledgeManifest.FILE),
          "---\nname: " + name + "\ndescription: " + desc + "\nbackend: local\n---\n");
    } catch (IOException e) {
      throw new UncheckedIOException("创建知识库失败: " + name, e);
    }
  }

  @Override
  public void updateBase(String name, String description) {
    Path dir = RealPathBoundary.requireWithin(knowledgeRoot, knowledgeRoot.resolve(name));
    KnowledgeManifest.read(dir); // 校验存在且合法
    String desc = description == null ? "" : description.replace('\r', ' ').replace('\n', ' ');
    try {
      Files.writeString(
          dir.resolve(KnowledgeManifest.FILE),
          "---\nname: " + name + "\ndescription: " + desc + "\nbackend: local\n---\n");
    } catch (IOException e) {
      throw new UncheckedIOException("更新知识库清单失败: " + name, e);
    }
  }

  @Override
  public void deleteBase(String name) {
    Path dir = RealPathBoundary.requireWithin(knowledgeRoot, knowledgeRoot.resolve(name));
    if (!Files.isDirectory(dir)) {
      throw new IllegalArgumentException("知识库不存在: " + name);
    }
    indexService.deleteBase(name);
    try (Stream<Path> walk = Files.walk(dir)) {
      List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
      for (Path path : paths) {
        Files.delete(path);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("删除知识库目录失败: " + name, e);
    }
  }

  @Override
  public DocumentStatus importDocument(String kbName, String relPath) {
    return indexService.importDocument(kbName, relPath);
  }

  @Override
  public void deleteDocument(String kbName, String relPath) {
    indexService.deleteDocument(kbName, relPath);
  }

  @Override
  public void rebuild(String kbName) {
    indexService.rebuild(kbName);
  }

  @Override
  public List<DocumentStatus> status(String kbName) {
    return indexService.status(kbName);
  }
}
