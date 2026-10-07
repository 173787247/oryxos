package io.oryxos.memory;

import io.oryxos.core.embedding.TextEmbedder;
import io.oryxos.core.embedding.VectorCodec;
import io.oryxos.core.memory.MemoryEntryView;
import io.oryxos.storage.MemoryVectorEntity;
import io.oryxos.storage.MemoryVectorRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 归档记忆的向量索引维护（015 FR-005/006/007）——写路径落库优先：本体先写、向量化异步补，任何索引异常 都不冒泡（零丢失，与知识库「向量化失败拒收」相反的取舍）。有界执行器 1
 * worker + 有限队列，队满静默丢弃、 随启动对账补齐；对账幂等（补缺失、清孤儿、模型/维度变更整体重建——维度先确认， 未知维度不判陈旧）。仅归档条目入索引（core 不参与检索）。
 */
public class MemoryVectorIndex {

  private static final Logger log = LoggerFactory.getLogger(MemoryVectorIndex.class);

  /** 队列容量：写入速率远低于向量化速率，256 覆盖突发；溢出条目由对账兜底（FR-005 零丢失指本体）。 */
  private static final int QUEUE_CAPACITY = 256;

  private static final long WORKER_KEEP_ALIVE_SECONDS = 30;

  /** 维度探测文本：embedder 惰性时用一次真实调用取回当前维度（FR-007），与记忆内容无关。 */
  private static final String DIMENSION_PROBE = "oryxos-dimension-probe";

  private final MemoryVectorRepository repository;
  private final TextEmbedder embedder;
  private final Executor executor;

  /** 本进程已确认的向量维度（0 = 未知）——惰性 embedder 首次真实调用前 dimensions() 报 0，见 currentDimension()。 */
  private volatile int knownDimension;

  /** 维度是否已尝试确认（失败也算）：每个进程最多探测一次，端点不可用时不让每个作用域各付一次超时。 */
  private volatile boolean dimensionLookupDone;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository/embedder/executor 均为装配注入的共享协作者，构造注入存同一引用正是意图")
  public MemoryVectorIndex(
      MemoryVectorRepository repository, TextEmbedder embedder, Executor executor) {
    this.repository = repository;
    this.embedder = embedder;
    this.executor = executor;
  }

  /** 生产装配形态：1 worker + 有界队列 + 队满丢弃（守护线程，不阻塞停机）。 */
  public static MemoryVectorIndex withBoundedExecutor(
      MemoryVectorRepository repository, TextEmbedder embedder) {
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            1,
            1,
            WORKER_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
              Thread thread = new Thread(runnable, "memory-vector-indexer");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.DiscardPolicy());
    executor.allowCoreThreadTimeOut(true);
    return new MemoryVectorIndex(repository, embedder, executor);
  }

  /** 跨档统一条目寻址（data-model §1）：sha256(agent|scope|条目原文)；索引只收归档，scope 固定 ARCHIVAL。 */
  public static String entryHash(String agentName, String content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed =
          digest.digest((agentName + "|ARCHIVAL|" + content).getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hashed.length * 2);
      for (byte b : hashed) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
    }
  }

  /** 异步入队向量化：执行器拒绝（队满/已停）也不冒泡——本体已落库，索引随对账补齐。 */
  public void enqueue(String agentName, MemoryEntryView entry) {
    try {
      executor.execute(() -> indexSafely(agentName, entry));
    } catch (RejectedExecutionException e) {
      log.debug("记忆向量化队列已满，条目暂不索引（随对账补齐）");
    }
  }

  /**
   * 启动对账（幂等）：清模型或维度不一致的行（整体重建，不混比新旧向量）、清本体已不存在的孤儿行、补缺失行。 「已索引」= 表中有当前模型（维度已确认时还须当前维度）的对应行，无单独状态列。
   * 维度先确认再判行：确认失败（0 = 未知）时只比模型——把「未知」当「变了」会在每次重启把全表判陈旧。
   */
  public void reconcile(String agentName, List<MemoryEntryView> archivalEntries) {
    repository.deleteByEmbeddingModelNot(embedder.modelId());
    Map<String, MemoryEntryView> live = new LinkedHashMap<>();
    for (MemoryEntryView entry : archivalEntries) {
      live.putIfAbsent(entryHash(agentName, entry.content()), entry);
    }
    Set<String> existing = new HashSet<>();
    List<String> stale = new ArrayList<>();
    List<MemoryVectorEntity> rows = repository.findByAgentName(agentName);
    int dimension = rows.isEmpty() ? 0 : currentDimension();
    for (MemoryVectorEntity row : rows) {
      // 本体仍在、且向量出自当前模型与当前维度，才算已索引；孤儿与陈旧行一并清掉，按 missing 重建
      if (live.containsKey(row.getEntryHash()) && isCurrent(row, dimension)) {
        existing.add(row.getEntryHash());
      } else {
        stale.add(row.getEntryHash());
      }
    }
    if (!stale.isEmpty()) {
      repository.deleteByAgentNameAndEntryHashIn(agentName, stale);
    }
    List<MemoryEntryView> missing = new ArrayList<>();
    live.forEach(
        (hash, entry) -> {
          if (!existing.contains(hash)) {
            missing.add(entry);
          }
        });
    if (missing.isEmpty()) {
      return;
    }
    // 补缺失打包成单个批任务：只占一个队列槽、worker 内逐条建——大规模存量（万条）一次对账即可
    // 补齐，不受逐条入队被有界队列丢弃的限制（丢的只可能是整批，下次对账重试）。
    try {
      executor.execute(() -> missing.forEach(entry -> indexSafely(agentName, entry)));
    } catch (RejectedExecutionException e) {
      log.debug("对账批任务入队被拒（队满/已停），待下次对账重试");
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志中的异常消息已经 sanitize() 消去 CR/LF；taint 分析不跨方法追踪该消毒，故局部抑制")
  private void indexSafely(String agentName, MemoryEntryView entry) {
    try {
      index(agentName, entry);
    } catch (RuntimeException e) {
      log.warn("记忆向量化失败（本体已落库，随对账补齐）: {}", sanitize(e.getMessage()));
    }
  }

  /** 日志参数消毒：去掉换行，防日志伪造（CRLF injection）。 */
  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }

  /**
   * 当前向量维度；0 = 未知。embedder 是惰性的（ProviderEmbeddingModelFactory 只在首次真实调用后才确定维度），
   * 而启动对账发生在任何真实调用之前——所以这里用一次探测调用取回真实维度：不取，就无法区分「维度没变」与
   * 「维度变了」，只能二选一（全表重建，或永不重建）。探测文本与记忆内容无关，向量即取即弃；确认过（含失败） 就不再调用，端点不可用时也不让每个作用域各付一次超时。
   */
  private int currentDimension() {
    int known = knownDimension;
    if (known > 0 || dimensionLookupDone) {
      return known;
    }
    dimensionLookupDone = true;
    int reported = embedder.dimensions();
    if (reported > 0) {
      knownDimension = reported;
      return reported;
    }
    try {
      int probed = embedder.embed(DIMENSION_PROBE).length;
      knownDimension = probed;
      return probed;
    } catch (RuntimeException e) {
      log.warn("向量维度探测失败，本轮对账不按维度判陈旧（下次启动重试）: {}", sanitize(e.getMessage()));
      return 0;
    }
  }

  /**
   * 行是否已由当前 embedder 建好：模型必须一致；维度只在已确认（{@code dimension > 0}）时比较。维度也是身份的一部分——同一 modelId 换了
   * dimensions（或上游同名模型换了隐层宽度）时旧行不能复用，否则语义路按维度过滤后恒空（FR-007「维度不一致 MUST
   * 自动重建」）。未知维度不构成不一致：把「还没取到」当「变了」会让每次进程重启都全表重建。
   */
  private boolean isCurrent(MemoryVectorEntity row, int dimension) {
    return embedder.modelId().equals(row.getEmbeddingModel())
        && (dimension <= 0 || row.getDim() == dimension);
  }

  private void index(String agentName, MemoryEntryView entry) {
    String hash = entryHash(agentName, entry.content());
    Optional<MemoryVectorEntity> existing = repository.findByAgentNameAndEntryHash(agentName, hash);
    // 与对账同口径：维度未知（惰性 embedder 首次真实调用前）时只比模型，不把「未知」当「变了」；
    // 维度确认只在启动对账做一次（currentDimension），写路径不为一次判定多发一次真实调用
    if (existing.isPresent() && isCurrent(existing.get(), embedder.dimensions())) {
      return; // 幂等：同条目同模型同维度已索引
    }
    float[] vector = embedder.embed(entry.content());
    MemoryVectorEntity entity = existing.orElseGet(MemoryVectorEntity::new);
    entity.setEntryHash(hash);
    entity.setAgentName(agentName);
    entity.setContent(entry.content());
    entity.setEmbedding(VectorCodec.encode(vector));
    entity.setDim(vector.length);
    entity.setEmbeddingModel(embedder.modelId());
    entity.setEntryTime(entry.time());
    repository.save(entity);
  }
}
