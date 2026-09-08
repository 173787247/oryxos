package io.oryxos.web.controller;

import io.oryxos.core.fs.RealPathBoundary;
import io.oryxos.core.knowledge.KnowledgeAdmin;
import io.oryxos.core.knowledge.KnowledgeBackend;
import io.oryxos.core.knowledge.KnowledgeBackendRegistry;
import io.oryxos.core.knowledge.KnowledgeBindingService;
import io.oryxos.core.knowledge.KnowledgeImportException;
import io.oryxos.core.knowledge.KnowledgeManifest;
import io.oryxos.core.knowledge.KnowledgeService;
import io.oryxos.core.knowledge.model.KnowledgeBaseInfo;
import io.oryxos.core.knowledge.model.KnowledgeCapabilities;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.CreateKnowledgeBaseRequest;
import io.oryxos.web.controller.dto.KnowledgeBaseDetailView;
import io.oryxos.web.controller.dto.KnowledgeBaseView;
import io.oryxos.web.controller.dto.KnowledgeDocumentView;
import io.oryxos.web.controller.dto.UpdateKnowledgeBaseRequest;
import io.oryxos.web.error.ResourceNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库 REST 端点（FR-008）：全生命周期 + 两段式上传 + 索引状态 + 双缓冲重建。 管理类操作进入时按目标库后端的能力声明门禁——未声明能力 → 400
 * 可读拒绝，绝不半执行（FR-006 / SC-011）； 删除受 Agent 引用保护 → 409 + 引用清单（FR-011）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "core-stage web API is unauthenticated by design (internal network + gateway); auth is extension-phase. 协作者是 Spring 注入的共享单例，构造注入共享同一引用正是意图。")
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeApiController {

  /** 上传文件名白名单：库内相对文件名，不允许路径分隔符（路径遍历在入口挡掉）。 */
  private static final Pattern SAFE_FILE_NAME = Pattern.compile("[A-Za-z0-9\\u4e00-\\u9fa5._-]+");

  private final KnowledgeService knowledgeService;
  private final KnowledgeBackendRegistry backendRegistry;
  private final KnowledgeBindingService bindingService;
  private final io.oryxos.web.knowledge.KnowledgeMetricsService metricsService;
  private final Path knowledgeRoot;

  public KnowledgeApiController(
      KnowledgeService knowledgeService,
      KnowledgeBackendRegistry backendRegistry,
      KnowledgeBindingService bindingService,
      String oryxosRoot) {
    this(knowledgeService, backendRegistry, bindingService, null, oryxosRoot);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public KnowledgeApiController(
      KnowledgeService knowledgeService,
      KnowledgeBackendRegistry backendRegistry,
      KnowledgeBindingService bindingService,
      io.oryxos.web.knowledge.KnowledgeMetricsService metricsService,
      @Value("${oryxos.root:.oryxos}") String oryxosRoot) {
    this.knowledgeService = knowledgeService;
    this.backendRegistry = backendRegistry;
    this.bindingService = bindingService;
    this.metricsService = metricsService;
    this.knowledgeRoot = Path.of(oryxosRoot).resolve("knowledge").toAbsolutePath().normalize();
  }

  /** 使用看板（FR-023）：只聚合审计数据；时间窗缺省最近 30 天。 */
  @GetMapping("/{name}/metrics")
  public ApiResponse<io.oryxos.web.controller.dto.KnowledgeMetricsView> metrics(
      @PathVariable String name,
      @RequestParam(value = "from", required = false) String from,
      @RequestParam(value = "to", required = false) String to) {
    requireBase(name);
    if (metricsService == null) {
      throw new IllegalStateException("知识库看板服务未装配");
    }
    java.time.Instant toInstant = parseInstant(to, java.time.Instant.now());
    java.time.Instant fromInstant =
        parseInstant(from, toInstant.minus(java.time.Duration.ofDays(30)));
    if (fromInstant.isAfter(toInstant)) {
      throw new IllegalArgumentException("时间窗非法：from 晚于 to");
    }
    return ApiResponse.ok(metricsService.compute(name, fromInstant, toInstant));
  }

  private static java.time.Instant parseInstant(String raw, java.time.Instant fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return java.time.Instant.parse(raw);
    } catch (java.time.format.DateTimeParseException e) {
      throw new IllegalArgumentException("时间参数须为 ISO-8601 Instant（如 2026-08-19T00:00:00Z）: " + raw);
    }
  }

  @GetMapping
  public ApiResponse<List<KnowledgeBaseView>> list() {
    return ApiResponse.ok(knowledgeService.listBases().stream().map(this::view).toList());
  }

  @PostMapping
  public ApiResponse<KnowledgeBaseView> create(@RequestBody CreateKnowledgeBaseRequest req) {
    if (req == null || req.name() == null || req.name().isBlank()) {
      throw new IllegalArgumentException("知识库名为空");
    }
    if (req.description() == null || req.description().isBlank()) {
      throw new IllegalArgumentException("知识库描述为空（描述会注入 Agent 上下文，必填）");
    }
    String backendName =
        req.backend() == null || req.backend().isBlank()
            ? KnowledgeBackendRegistry.LOCAL
            : req.backend();
    KnowledgeBackend backend = requireBackend(backendName);
    admin(backend, KnowledgeCapabilities::createDelete, "创建")
        .createBase(req.name(), req.description());
    return ApiResponse.ok(
        knowledgeService.listBases().stream()
            .filter(info -> info.name().equals(req.name()))
            .findFirst()
            .map(this::view)
            .orElseThrow(() -> new IllegalStateException("知识库创建后未出现在清单中: " + req.name())));
  }

  @GetMapping("/{name}")
  public ApiResponse<KnowledgeBaseDetailView> detail(@PathVariable String name) {
    KnowledgeManifest manifest = requireBase(name);
    KnowledgeBackend backend = requireBackend(manifest.backend());
    List<KnowledgeDocumentView> documents =
        backend.capabilities().status()
            ? backend.admin().orElseThrow().status(name).stream()
                .map(KnowledgeDocumentView::from)
                .toList()
            : List.of();
    KnowledgeBaseView base =
        knowledgeService.listBases().stream()
            .filter(info -> info.name().equals(name))
            .findFirst()
            .map(this::view)
            .orElseThrow(() -> new ResourceNotFoundException("知识库不存在: " + name));
    return ApiResponse.ok(new KnowledgeBaseDetailView(base, documents));
  }

  @PatchMapping("/{name}")
  public ApiResponse<KnowledgeBaseView> update(
      @PathVariable String name, @RequestBody UpdateKnowledgeBaseRequest req) {
    if (req == null || req.description() == null || req.description().isBlank()) {
      throw new IllegalArgumentException("知识库描述为空");
    }
    KnowledgeManifest manifest = requireBase(name);
    KnowledgeBackend backend = requireBackend(manifest.backend());
    admin(backend, KnowledgeCapabilities::createDelete, "修改").updateBase(name, req.description());
    return ApiResponse.ok(
        knowledgeService.listBases().stream()
            .filter(info -> info.name().equals(name))
            .findFirst()
            .map(this::view)
            .orElseThrow(() -> new ResourceNotFoundException("知识库不存在: " + name)));
  }

  /** 删除：引用保护先行（409 + 引用 Agent 清单），再按能力门禁与后端执行。 */
  @DeleteMapping("/{name}")
  public ApiResponse<Void> delete(@PathVariable String name) {
    KnowledgeManifest manifest = requireBase(name);
    bindingService.ensureDeletable(name); // 被引用 → KnowledgeReferencedException → 409
    KnowledgeBackend backend = requireBackend(manifest.backend());
    admin(backend, KnowledgeCapabilities::createDelete, "删除").deleteBase(name);
    return ApiResponse.ok(null);
  }

  /**
   * 两段式上传（Clarify-Q3）：同步段落盘 + 解析校验——不支持类型/扫描件/超限当场 400（含原因），
   * 校验失败即清理落盘文件不留半成品；切分向量化由后台虚拟线程推进，状态可随时查。
   */
  @PostMapping("/{name}/documents")
  public ApiResponse<KnowledgeDocumentView> upload(
      @PathVariable String name, @RequestParam("file") MultipartFile file) {
    KnowledgeManifest manifest = requireBase(name);
    KnowledgeBackend backend = requireBackend(manifest.backend());
    KnowledgeAdmin admin = admin(backend, KnowledgeCapabilities::importDocs, "上传文档");
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("上传文件为空");
    }
    String fileName = safeFileName(file.getOriginalFilename());
    Path target =
        RealPathBoundary.requireWithin(
            knowledgeRoot, knowledgeRoot.resolve(name).resolve(fileName));
    try {
      Files.write(target, file.getBytes());
    } catch (IOException e) {
      throw new UncheckedIOException("文档落盘失败: " + fileName, e);
    }
    try {
      return ApiResponse.ok(KnowledgeDocumentView.from(admin.importDocument(name, fileName)));
    } catch (KnowledgeImportException e) {
      deleteQuietly(target); // 入口即拒绝：不留半完成文件（Edge Cases）
      throw e;
    }
  }

  /** 删单个文档：索引片段级联清理 + 源文件一并删除（管理台视角文档即文件）。 */
  @DeleteMapping("/{name}/documents")
  public ApiResponse<Void> deleteDocument(
      @PathVariable String name, @RequestParam("path") String relPath) {
    KnowledgeManifest manifest = requireBase(name);
    KnowledgeBackend backend = requireBackend(manifest.backend());
    KnowledgeAdmin admin = admin(backend, KnowledgeCapabilities::importDocs, "删除文档");
    Path target =
        RealPathBoundary.requireWithin(knowledgeRoot, knowledgeRoot.resolve(name).resolve(relPath));
    admin.deleteDocument(name, relPath);
    deleteQuietly(target);
    return ApiResponse.ok(null);
  }

  @GetMapping("/{name}/status")
  public ApiResponse<List<KnowledgeDocumentView>> status(@PathVariable String name) {
    KnowledgeManifest manifest = requireBase(name);
    KnowledgeBackend backend = requireBackend(manifest.backend());
    return ApiResponse.ok(
        admin(backend, KnowledgeCapabilities::status, "查询索引状态").status(name).stream()
            .map(KnowledgeDocumentView::from)
            .toList());
  }

  /** 双缓冲重建（FR-024）：旧索引持续服务，新索引就绪原子切换；失败旧索引不受影响（503 + 可读原因）。 */
  @PostMapping("/{name}/reindex")
  public ApiResponse<List<KnowledgeDocumentView>> reindex(@PathVariable String name) {
    KnowledgeManifest manifest = requireBase(name);
    KnowledgeBackend backend = requireBackend(manifest.backend());
    KnowledgeAdmin admin = admin(backend, KnowledgeCapabilities::rebuild, "重建索引");
    admin.rebuild(name);
    return status(name);
  }

  private KnowledgeBaseView view(KnowledgeBaseInfo info) {
    KnowledgeCapabilities capabilities =
        backendRegistry.byName(info.backend()).map(KnowledgeBackend::capabilities).orElse(null);
    return KnowledgeBaseView.from(info, capabilities);
  }

  private KnowledgeManifest requireBase(String name) {
    Path dir = knowledgeRoot.resolve(name);
    if (!Files.isDirectory(dir)) {
      throw new ResourceNotFoundException("知识库不存在: " + name);
    }
    return KnowledgeManifest.read(dir);
  }

  private KnowledgeBackend requireBackend(String backendName) {
    return backendRegistry
        .byName(backendName)
        .orElseThrow(() -> new IllegalArgumentException("知识库后端未注册: " + backendName));
  }

  /** 能力门禁（FR-006）：未声明能力在入口可读拒绝，不落到后端实现。 */
  private static KnowledgeAdmin admin(
      KnowledgeBackend backend, Predicate<KnowledgeCapabilities> capability, String operation) {
    if (!capability.test(backend.capabilities()) || backend.admin().isEmpty()) {
      throw new IllegalArgumentException("该知识库后端不支持此操作（" + operation + "）: " + backend.name());
    }
    return backend.admin().orElseThrow();
  }

  private static String safeFileName(String original) {
    java.nio.file.Path leaf = original == null ? null : Path.of(original).getFileName();
    String fileName = leaf == null ? "" : leaf.toString();
    if (fileName.isBlank() || !SAFE_FILE_NAME.matcher(fileName).matches()) {
      throw new IllegalArgumentException("非法文件名（只允许中英文/数字/点/下划线/连字符）: " + original);
    }
    return fileName;
  }

  private static void deleteQuietly(Path target) {
    try {
      Files.deleteIfExists(target);
    } catch (IOException ignored) {
      // 清理失败不影响主流程；对账与重建可收敛
    }
  }
}
