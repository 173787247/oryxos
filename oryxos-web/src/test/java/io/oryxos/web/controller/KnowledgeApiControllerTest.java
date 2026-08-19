package io.oryxos.web.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.embedding.TextEmbedder;
import io.oryxos.core.knowledge.KnowledgeAdmin;
import io.oryxos.core.knowledge.KnowledgeBackend;
import io.oryxos.core.knowledge.KnowledgeBackendRegistry;
import io.oryxos.core.knowledge.KnowledgeBindingService;
import io.oryxos.core.knowledge.KnowledgeService;
import io.oryxos.core.knowledge.KnowledgeServiceImpl;
import io.oryxos.core.knowledge.model.KnowledgeCapabilities;
import io.oryxos.core.knowledge.model.KnowledgeHit;
import io.oryxos.core.knowledge.model.KnowledgeQuery;
import io.oryxos.knowledge.LocalKnowledgeBackend;
import io.oryxos.knowledge.index.KnowledgeIndexService;
import io.oryxos.knowledge.store.InMemoryChunkStore;
import io.oryxos.web.GlobalExceptionHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * T031：知识库 REST 全生命周期（FR-008）——两段式上传当场拒绝、能力门禁 400、引用保护 409、重名 409/400。 用真实本地后端（内存存储档）+
 * 仅检索测试桩，同一套端点核验能力感知（SC-011 门禁面）。
 */
class KnowledgeApiControllerTest {

  @TempDir Path root;

  private Path kbRoot;
  private KnowledgeBindingService bindings;
  private MockMvc mvc;

  @BeforeEach
  void setUp() throws Exception {
    kbRoot = Files.createDirectories(root.resolve("knowledge"));
    Files.createDirectories(root.resolve("agents"));
    Supplier<TextEmbedder> embedder = () -> new HashEmbedder();
    InMemoryChunkStore store = new InMemoryChunkStore();
    KnowledgeIndexService indexService =
        new KnowledgeIndexService(kbRoot, store, embedder, Runnable::run);
    LocalKnowledgeBackend local = new LocalKnowledgeBackend(kbRoot, store, indexService, embedder);
    KnowledgeBackendRegistry registry = new KnowledgeBackendRegistry();
    registry.register(local);
    registry.register(retrieveOnlyStub());
    bindings = new KnowledgeBindingService(root);
    KnowledgeService service = new KnowledgeServiceImpl(kbRoot, bindings, registry);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new KnowledgeApiController(service, registry, bindings, root.toString()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("建库 → 列表可见（含能力集）；重名 → 400")
  void createThenListWithCapabilities() throws Exception {
    createBase("ops-manual", "运维手册");

    mvc.perform(get("/api/v1/knowledge"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].name").value("ops-manual"))
        .andExpect(jsonPath("$.data[0].backend").value("local"))
        .andExpect(jsonPath("$.data[0].indexStatus").value("空"))
        .andExpect(jsonPath("$.data[0].capabilities.importDocs").value(true))
        .andExpect(jsonPath("$.data[0].capabilities.rerank").value(false));

    mvc.perform(
            post("/api/v1/knowledge")
                .contentType("application/json")
                .content("{\"name\":\"ops-manual\",\"description\":\"重名\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("两段式上传：md 落盘校验通过 → 后台索引 READY；扫描外类型当场 400 且不留半成品")
  void twoPhaseUploadAndEntryRejection() throws Exception {
    createBase("ops-manual", "运维手册");

    mvc.perform(
            multipart("/api/v1/knowledge/ops-manual/documents")
                .file(new MockMultipartFile("file", "disk.md", null, "# 磁盘告警\n\n处置步骤".getBytes())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.relPath").value("disk.md"));

    // 直接执行器：后台段已同步完成 → 状态机推进到 READY（Clarify-Q3 可随时查询）
    mvc.perform(get("/api/v1/knowledge/ops-manual/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].state").value("READY"))
        .andExpect(jsonPath("$.data[0].chunkCount").value(1));

    // 不支持类型：当场 400 + 可读原因，且落盘文件被清理（入口即拒绝不留半完成状态）
    mvc.perform(
            multipart("/api/v1/knowledge/ops-manual/documents")
                .file(new MockMultipartFile("file", "report.docx", null, "x".getBytes())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不支持")));
    assertFalse(Files.exists(kbRoot.resolve("ops-manual/report.docx")), "校验失败的落盘文件必须清理");
  }

  @Test
  @DisplayName("详情/改描述/删文档/重建")
  void detailUpdateDeleteDocReindex() throws Exception {
    createBase("ops-manual", "运维手册");
    mvc.perform(
            multipart("/api/v1/knowledge/ops-manual/documents")
                .file(new MockMultipartFile("file", "disk.md", null, "# 内容".getBytes())))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/knowledge/ops-manual"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.base.indexStatus").value("就绪"))
        .andExpect(jsonPath("$.data.documents[0].relPath").value("disk.md"));

    mvc.perform(
            patch("/api/v1/knowledge/ops-manual")
                .contentType("application/json")
                .content("{\"description\":\"新描述\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.description").value("新描述"));

    mvc.perform(post("/api/v1/knowledge/ops-manual/reindex"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].state").value("READY"));

    mvc.perform(delete("/api/v1/knowledge/ops-manual/documents").param("path", "disk.md"))
        .andExpect(status().isOk());
    assertFalse(Files.exists(kbRoot.resolve("ops-manual/disk.md")));
  }

  @Test
  @DisplayName("删除被 Agent 引用的库 → 409 + 引用清单；解绑后可删（FR-011）")
  void deleteIsReferenceProtected() throws Exception {
    createBase("ops-manual", "运维手册");
    Files.writeString(
        Files.createDirectories(root.resolve("agents/ops")).resolve("AGENT.md"),
        "---\nname: ops\n---\nbody");
    bindings.bind("ops", "ops-manual");

    mvc.perform(delete("/api/v1/knowledge/ops-manual"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.data.references[0].agentName").value("ops"));

    bindings.unbind("ops", "ops-manual");
    mvc.perform(delete("/api/v1/knowledge/ops-manual")).andExpect(status().isOk());
    assertFalse(Files.exists(kbRoot.resolve("ops-manual")));
  }

  @Test
  @DisplayName("能力门禁：仅检索后端的管理操作入口即 400 可读拒绝（FR-006 / SC-011）；不存在 → 404")
  void capabilityGateAndNotFound() throws Exception {
    Path remote = Files.createDirectories(kbRoot.resolve("remote-kb"));
    Files.writeString(
        remote.resolve("KNOWLEDGE.md"),
        "---\nname: remote-kb\ndescription: 远程库\nbackend: stub\n---\n");

    mvc.perform(post("/api/v1/knowledge/remote-kb/reindex"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不支持此操作")));
    mvc.perform(
            multipart("/api/v1/knowledge/remote-kb/documents")
                .file(new MockMultipartFile("file", "a.md", null, "x".getBytes())))
        .andExpect(status().isBadRequest());
    // 列表照常展示（能力集为仅检索，前端据此不渲染入口）
    mvc.perform(get("/api/v1/knowledge"))
        .andExpect(jsonPath("$.data[0].capabilities.importDocs").value(false));

    mvc.perform(get("/api/v1/knowledge/nope")).andExpect(status().isNotFound());
  }

  private void createBase(String name, String description) throws Exception {
    mvc.perform(
            post("/api/v1/knowledge")
                .contentType("application/json")
                .content("{\"name\":\"" + name + "\",\"description\":\"" + description + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  /** 仅声明检索能力的远程测试桩（US5 门禁面先行核验；契约三同在 impl-C 契约测试收口）。 */
  private static KnowledgeBackend retrieveOnlyStub() {
    return new KnowledgeBackend() {
      @Override
      public String name() {
        return "stub";
      }

      @Override
      public KnowledgeCapabilities capabilities() {
        return KnowledgeCapabilities.retrieveOnly();
      }

      @Override
      public Optional<KnowledgeAdmin> admin() {
        return Optional.empty();
      }

      @Override
      public List<KnowledgeHit> retrieve(KnowledgeQuery query) {
        return List.of();
      }
    };
  }

  /** 确定性字符直方图向量（测试用）。 */
  private static final class HashEmbedder implements TextEmbedder {
    @Override
    public float[] embed(String text) {
      float[] vector = new float[8];
      for (int i = 0; i < text.length(); i++) {
        vector[text.charAt(i) % 8] += 1;
      }
      return vector;
    }

    @Override
    public String modelId() {
      return "test/v1";
    }

    @Override
    public int dimensions() {
      return 8;
    }
  }
}
