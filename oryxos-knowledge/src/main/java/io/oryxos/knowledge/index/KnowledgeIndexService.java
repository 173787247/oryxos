package io.oryxos.knowledge.index;

import io.oryxos.core.embedding.TextEmbedder;
import io.oryxos.core.fs.RealPathBoundary;
import io.oryxos.core.knowledge.KnowledgeImportException;
import io.oryxos.core.knowledge.KnowledgeManifest;
import io.oryxos.core.knowledge.model.DocumentState;
import io.oryxos.core.knowledge.model.DocumentStatus;
import io.oryxos.knowledge.store.ChunkStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 索引流水线（FR-003 / Clarify-Q1/Q3）：两段式导入——同步落盘解析校验（失败入口即拒绝）， 切分与向量化由虚拟线程后台推进，状态机 PENDING → INDEXING →
 * READY / FAILED 可随时查询； 重建走双缓冲——旧代持续服务，新代就绪原子切换、失败即弃且旧代不受影响（FR-024）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "CRLF_INJECTION_LOGS",
    justification = "所有不可信字符串入日志前均经 sanitize 去除 CR/LF。")
public class KnowledgeIndexService {

  private static final Logger LOG = LoggerFactory.getLogger(KnowledgeIndexService.class);

  /** 单文档大小上限（Edge Cases：超大文档拒绝并提示）。 */
  static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

  private final Path knowledgeRoot;
  private final ChunkStore store;
  private final Supplier<TextEmbedder> embedderSupplier;
  private final Executor executor;
  private final List<DocumentParser> parsers =
      List.of(new MarkdownParser(), new TextParser(), new PdfParser());
  private final Chunker chunker = new Chunker();
  private final ConcurrentHashMap<String, Long> activeGenerations = new ConcurrentHashMap<>();

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "store/executor 为装配层注入的共享单例，构造注入存同一引用正是意图（镜像既有 SuppressFBWarnings 模式）。")
  public KnowledgeIndexService(
      Path knowledgeRoot,
      ChunkStore store,
      Supplier<TextEmbedder> embedderSupplier,
      Executor executor) {
    this.knowledgeRoot = knowledgeRoot.toAbsolutePath().normalize();
    this.store = store;
    this.embedderSupplier = embedderSupplier;
    this.executor = executor;
  }

  /** 当前对检索可见的索引代号：从存量推断一次后内存维护（启动对账重算）。 */
  public long activeGeneration(String kbName) {
    return activeGenerations.computeIfAbsent(
        kbName,
        kb ->
            store.allDocuments(kb).stream()
                .filter(doc -> doc.state() == DocumentState.READY)
                .mapToLong(ChunkStore.DocumentRecord::generation)
                .max()
                .orElse(0L));
  }

  /**
   * 两段式导入的同步段：校验 + 登记 PENDING + 提交后台索引；返回可查询的初始状态。 synchronized 与 rebuild/reconcile 同款：并发导入（对账 +
   * 手动）若交错，deleteChunksOf/saveChunks 会翻倍落片段， 后到的新建还可能撞 UNIQUE(kb_name,rel_path,generation) 直接 500。
   */
  public synchronized DocumentStatus importDocument(String kbName, String relPath) {
    Path file = requireDocumentFile(kbName, relPath);
    List<ParsedUnit> units = parseValidated(file);
    long generation = activeGeneration(kbName);
    String sha = sha256(file);
    ChunkStore.DocumentRecord existing =
        store.findDocument(kbName, relPath, generation).orElse(null);
    if (existing != null
        && existing.state() == DocumentState.READY
        && MessageDigest.isEqual(
            existing.sha256().getBytes(StandardCharsets.UTF_8),
            sha.getBytes(StandardCharsets.UTF_8))) {
      return toStatus(existing);
    }
    ChunkStore.DocumentRecord pending =
        store.saveDocument(
            new ChunkStore.DocumentRecord(
                existing == null ? null : existing.id(),
                kbName,
                relPath,
                sha,
                DocumentState.PENDING,
                null,
                0,
                generation,
                existing == null ? null : existing.indexedAt()));
    long documentId = pending.id();
    executor.execute(() -> indexDocument(documentId, kbName, relPath, generation, units));
    return toStatus(pending);
  }

  /** 双缓冲重建（FR-024）：新代整体成功才切换；任一文档失败则丢弃新代并抛可读原因，旧代照常服务。 */
  public synchronized void rebuild(String kbName) {
    long oldGeneration = activeGeneration(kbName);
    long newGeneration = oldGeneration + 1;
    try {
      for (Path file : listSupportedFiles(kbDir(kbName))) {
        String relPath = kbDir(kbName).relativize(file).toString();
        ChunkStore.DocumentRecord record =
            store.saveDocument(
                new ChunkStore.DocumentRecord(
                    null,
                    kbName,
                    relPath,
                    sha256(file),
                    DocumentState.INDEXING,
                    null,
                    0,
                    newGeneration,
                    null));
        indexNow(record, parseValidated(file));
      }
      activeGenerations.put(kbName, newGeneration);
      store.deleteGenerationsBelow(kbName, newGeneration);
    } catch (RuntimeException e) {
      store.deleteGeneration(kbName, newGeneration);
      throw new IllegalStateException("重建索引失败（旧索引不受影响）: " + e.getMessage(), e);
    }
  }

  public List<DocumentStatus> status(String kbName) {
    return store.documents(kbName, activeGeneration(kbName)).stream()
        .map(KnowledgeIndexService::toStatus)
        .toList();
  }

  public void deleteDocument(String kbName, String relPath) {
    store
        .findDocument(kbName, relPath, activeGeneration(kbName))
        .ifPresent(doc -> store.deleteDocument(doc.id()));
  }

  public void deleteBase(String kbName) {
    store.deleteBase(kbName);
    activeGenerations.remove(kbName);
  }

  /**
   * 对账（FR-010）：目录 ⇄ 索引差异收敛——新文件/指纹变化/失败态 → 重新导入（后台段推进）； 文件消失 → 清掉索引行。启动与热加载共用；单个坏文件 WARN
   * 跳过、不拖垮整库（US4 场景 3）。
   */
  public synchronized void reconcile(String kbName) {
    Path kbDir = kbDir(kbName);
    long generation = activeGeneration(kbName);
    Map<String, ChunkStore.DocumentRecord> indexed = new java.util.HashMap<>();
    for (ChunkStore.DocumentRecord doc : store.documents(kbName, generation)) {
      indexed.put(doc.relPath(), doc);
    }
    java.util.Set<String> present = new java.util.HashSet<>();
    for (Path file : listSupportedFiles(kbDir)) {
      String relPath = kbDir.relativize(file).toString();
      present.add(relPath);
      ChunkStore.DocumentRecord existing = indexed.get(relPath);
      boolean changed =
          existing == null
              || existing.state() == DocumentState.FAILED
              || !MessageDigest.isEqual(
                  existing.sha256().getBytes(StandardCharsets.UTF_8),
                  sha256(file).getBytes(StandardCharsets.UTF_8));
      if (!changed) {
        continue;
      }
      try {
        importDocument(kbName, relPath);
      } catch (RuntimeException e) {
        LOG.warn(
            "对账导入失败，跳过 {}/{}: {}", sanitize(kbName), sanitize(relPath), sanitize(e.getMessage()));
      }
    }
    for (Map.Entry<String, ChunkStore.DocumentRecord> entry : indexed.entrySet()) {
      if (!present.contains(entry.getKey()) && entry.getValue().id() != null) {
        store.deleteDocument(entry.getValue().id()); // 文件已删：索引行与片段一并清（SC-006 不再命中）
      }
    }
  }

  /** 后台段：切分 + 向量化 + 落库；任何失败落 FAILED + 可读原因，可重试（FR-013 不静默丢弃）。 */
  private void indexDocument(
      long documentId, String kbName, String relPath, long generation, List<ParsedUnit> units) {
    ChunkStore.DocumentRecord record = store.findDocument(kbName, relPath, generation).orElse(null);
    if (record == null || record.id() == null || record.id() != documentId) {
      return; // 已被删除或替换，静默让位
    }
    try {
      indexNow(store.saveDocument(record.withState(DocumentState.INDEXING, null)), units);
    } catch (RuntimeException e) {
      LOG.warn(
          "知识文档索引失败: {}/{}: {}", sanitize(kbName), sanitize(relPath), sanitize(e.getMessage()));
      // 只在行还在时落 FAILED——索引期间被删除/替换的文档不得因此复活（TOCTOU）
      ChunkStore.DocumentRecord latest =
          store.findDocument(kbName, relPath, generation).orElse(null);
      if (latest != null && latest.id() != null && latest.id() == documentId) {
        store.saveDocument(record.withState(DocumentState.FAILED, readable(e)));
      }
    }
  }

  /** 同步完成一份文档的切分向量化落库并置 READY；失败向上抛（调用方决定 FAILED 或整体回滚）。 */
  private void indexNow(ChunkStore.DocumentRecord record, List<ParsedUnit> units) {
    TextEmbedder embedder = embedderSupplier.get();
    List<ChunkStore.ChunkRecord> chunkRecords = new ArrayList<>();
    int seq = 0;
    for (ParsedUnit unit : units) {
      for (String piece : chunker.split(unit.text())) {
        seq++;
        float[] vector = embedder.embed(piece);
        chunkRecords.add(
            new ChunkStore.ChunkRecord(
                null,
                record.id(),
                record.kbName(),
                seq,
                unit.pageNo(),
                piece,
                vector,
                embedder.modelId(),
                record.generation()));
      }
    }
    // TOCTOU 复检：embed 是秒级外部调用，窗口内文档可能已被删除/替换——检索取数不 join
    // documents 表，此刻若照常落库，已删文档的孤儿片段仍可被召回（违反 SC-006）
    ChunkStore.DocumentRecord current =
        store.findDocument(record.kbName(), record.relPath(), record.generation()).orElse(null);
    if (current == null || current.id() == null || !current.id().equals(record.id())) {
      throw new IllegalStateException("文档在索引期间被删除或替换，片段放弃落库");
    }
    store.deleteChunksOf(record.id());
    store.saveChunks(chunkRecords);
    store.saveDocument(record.ready(chunkRecords.size(), Instant.now()));
  }

  /** 同步校验段：类型受理、大小上限、可解析（扫描件在此拒绝）、非空。 */
  private List<ParsedUnit> parseValidated(Path file) {
    String fileName = String.valueOf(file.getFileName());
    DocumentParser parser =
        parsers.stream()
            .filter(candidate -> candidate.supports(fileName))
            .findFirst()
            .orElseThrow(
                () ->
                    new KnowledgeImportException(
                        "不支持的文档类型: " + fileName + "（支持 markdown / txt / 文本型 PDF）"));
    try {
      if (Files.size(file) > MAX_FILE_BYTES) {
        throw new KnowledgeImportException("文档超过 10MB 上限: " + fileName);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("读取文档大小失败: " + file, e);
    }
    List<ParsedUnit> units = parser.parse(file);
    if (units.isEmpty()) {
      throw new KnowledgeImportException("空文档，无可索引内容: " + fileName);
    }
    return units;
  }

  /**
   * 重建/对账用：列出库内全部受支持文档；软链、真实路径越界、不支持、空、超限的跳过并告警（Edge Cases）。
   *
   * <p>软链一律不跟随：库内的链接文件可能指向库外（channels.yaml、oryxos.db 等），跟随扫描会把外部敏感内容 切分、向量化入库再被检索命中；{@code
   * Files.walk} 本身不带 {@code FOLLOW_LINKS}（目录链不深入），这里再以 NOFOLLOW 拦掉叶子链接， 并对真实路径做库内边界复检（防祖先目录被换成软链等
   * TOCTOU），与工作区文件入口同一套判定口径。
   */
  List<Path> listSupportedFiles(Path kbDir) {
    try (Stream<Path> walk = Files.walk(kbDir)) {
      return walk.filter(this::isContentRegularFile)
          .filter(file -> !KnowledgeManifest.FILE.equals(String.valueOf(file.getFileName())))
          .filter(file -> withinKb(kbDir, file))
          .filter(this::scannable)
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("扫描知识库目录失败: " + kbDir, e);
    }
  }

  /** 知识库内容只收真实普通文件：文件/目录软链一律拒绝（带告警），其余按 NOFOLLOW 普通文件判定。 */
  private boolean isContentRegularFile(Path file) {
    if (Files.isSymbolicLink(file)) {
      LOG.warn("跳过软链接（知识库内容不跟随软链）: {}", sanitize(String.valueOf(file)));
      return false;
    }
    return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
  }

  /** 词法在库内不代表真实在库内：真实路径越界（软链逃逸 / 祖先目录被换）的文件跳过并告警，绝不索引。 */
  private boolean withinKb(Path kbDir, Path file) {
    if (RealPathBoundary.isWithin(kbDir, file)) {
      return true;
    }
    LOG.warn("跳过真实路径越出知识库的文件（疑似软链逃逸）: {}", sanitize(String.valueOf(file)));
    return false;
  }

  private boolean scannable(Path file) {
    String fileName = String.valueOf(file.getFileName());
    if (parsers.stream().noneMatch(parser -> parser.supports(fileName))) {
      LOG.warn("跳过不支持的文件类型: {}", sanitize(fileName));
      return false;
    }
    try {
      long size = Files.size(file);
      if (size == 0) {
        LOG.warn("跳过空文档: {}", sanitize(fileName));
        return false;
      }
      if (size > MAX_FILE_BYTES) {
        LOG.warn("跳过超过 10MB 上限的文档: {}", sanitize(fileName));
        return false;
      }
    } catch (IOException e) {
      LOG.warn("跳过不可读文件: {}", sanitize(fileName));
      return false;
    }
    return true;
  }

  private Path requireDocumentFile(String kbName, String relPath) {
    Path kbDir = kbDir(kbName);
    Path file = RealPathBoundary.requireWithin(kbDir, kbDir.resolve(relPath));
    if (!Files.isRegularFile(file)) {
      throw new KnowledgeImportException("文档不存在: " + kbName + "/" + relPath);
    }
    return file;
  }

  private Path kbDir(String kbName) {
    Path dir = knowledgeRoot.resolve(kbName);
    if (!Files.isDirectory(dir)) {
      throw new IllegalArgumentException("知识库不存在: " + kbName);
    }
    return dir;
  }

  private static DocumentStatus toStatus(ChunkStore.DocumentRecord record) {
    return new DocumentStatus(
        record.relPath(),
        record.state(),
        record.chunkCount(),
        record.failureReason(),
        record.indexedAt());
  }

  private static String sha256(Path file) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
    } catch (IOException e) {
      throw new UncheckedIOException("计算文档指纹失败: " + file, e);
    }
  }

  private static String readable(RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
