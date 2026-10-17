package io.oryxos.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.embedding.TextEmbedder;
import io.oryxos.core.knowledge.KnowledgeManifest;
import io.oryxos.core.knowledge.model.DocumentState;
import io.oryxos.core.knowledge.model.DocumentStatus;
import io.oryxos.core.knowledge.model.KnowledgeHit;
import io.oryxos.core.knowledge.model.KnowledgeQuery;
import io.oryxos.core.testing.SymlinkAssumptions;
import io.oryxos.knowledge.index.KnowledgeIndexService;
import io.oryxos.knowledge.store.InMemoryChunkStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 知识库名的边界：{@code kbName} 是库目录名，也一直是「未校验的路径片段」。这里钉住六个入口 （createBase / updateBase / deleteBase /
 * importDocument / rebuild，REST 入口见 {@code
 * KnowledgeApiControllerTest}）对越界名的统一拒绝，以及拒绝之后**没有副作用**：库外文件不入索引、别的库不被删。
 */
class KnowledgeBaseNameBoundaryTest {

  private static final String AGENT_SENTINEL = "AGENTSECRET-7f3d";
  private static final String ESCAPE_SENTINEL = "OUTSIDESECRET-9a1c";

  @TempDir Path workspace;

  private Path knowledgeRoot;
  private InMemoryChunkStore store;
  private KnowledgeIndexService indexService;
  private LocalKnowledgeBackend backend;
  private final List<Runnable> backgroundTasks = new ArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    knowledgeRoot = Files.createDirectories(workspace.resolve("knowledge"));
    // 与默认部署同形的 .oryxos：knowledge/ 与 agents/ 相邻，于是 kbName="../agents/demo" 的词法目标真实存在
    Path demo = Files.createDirectories(workspace.resolve("agents").resolve("demo"));
    Files.writeString(
        demo.resolve("AGENT.md"), "---\nname: demo\n---\n任务指令 " + AGENT_SENTINEL + "\n");
    Files.writeString(demo.resolve("MEMORY.md"), "# 记忆\n\n" + AGENT_SENTINEL + "\n");
    Supplier<TextEmbedder> embedder = () -> new TestEmbedder();
    store = new InMemoryChunkStore();
    indexService = new KnowledgeIndexService(knowledgeRoot, store, embedder, backgroundTasks::add);
    backend = new LocalKnowledgeBackend(knowledgeRoot, store, indexService, embedder);
    backend.createBase("ops", "运维手册");
    Files.writeString(knowledgeRoot.resolve("ops").resolve("disk.md"), "# 磁盘告警\n\n先查 inode 占用。\n");
  }

  @Test
  @DisplayName("kbName=\"../agents/demo\"：每个入口确定拒绝，AGENT.md/MEMORY.md 内容不进索引")
  void traversalNameIsRejectedAndAgentFilesNeverIndexed() {
    for (Runnable call :
        List.<Runnable>of(
            () -> backend.createBase("../agents/demo", "x"),
            () -> backend.updateBase("../agents/demo", "x"),
            () -> backend.deleteBase("../agents/demo"),
            () -> backend.importDocument("../agents/demo", "AGENT.md"),
            () -> backend.importDocument("../agents/demo", "MEMORY.md"),
            () -> backend.rebuild("../agents/demo"))) {
      IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, call::run);
      assertTrue(
          rejected.getMessage().contains("非法知识库名"),
          "拒绝原因必须点名规则（不再靠「目标目录恰好没有 KNOWLEDGE.md」）: " + rejected.getMessage());
    }

    runBackground();
    assertTrue(store.allDocuments("../agents/demo").isEmpty(), "越界目录不得留下索引记录");
    assertTrue(backend.status("../agents/demo").isEmpty(), "越界库名不得有可查状态");
    assertTrue(
        backend
            .retrieve(new KnowledgeQuery(AGENT_SENTINEL, 10, List.of("ops", ".", "../agents/demo")))
            .stream()
            .noneMatch(hit -> hit.content().contains(AGENT_SENTINEL)),
        "AGENT.md/MEMORY.md 的内容不得被切分进索引");

    // 越界目标本身不得被创建、改写或删除
    assertTrue(Files.isRegularFile(workspace.resolve("agents/demo/AGENT.md")));
    assertTrue(Files.isRegularFile(workspace.resolve("agents/demo/MEMORY.md")));
    assertFalse(Files.exists(workspace.resolve("agents/demo/KNOWLEDGE.md")), "不得往越界目录写清单");
  }

  @Test
  @DisplayName("kbName=\".\"：拒绝；deleteBase(\".\") 抛异常后两个库都还在（不是把整棵根删掉）")
  void dotNameCannotDeleteOtherBases() throws IOException {
    backend.createBase("faq", "常见问题");
    Files.writeString(knowledgeRoot.resolve("faq").resolve("faq.md"), "# 常见问题\n\n答案。\n");

    for (Runnable call :
        List.<Runnable>of(
            () -> backend.createBase(".", "x"),
            () -> backend.updateBase(".", "x"),
            () -> backend.deleteBase("."),
            () -> backend.importDocument(".", "ops/disk.md"),
            () -> backend.rebuild("."),
            () -> backend.deleteBase(".."))) {
      IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, call::run);
      assertTrue(rejected.getMessage().contains("非法知识库名"), "拒绝原因必须点名规则: " + rejected.getMessage());
    }

    // 这个 bug 的形态是「删掉了别的东西」：只断言抛异常不够，必须断言别的库还在
    assertEquals("ops", KnowledgeManifest.read(knowledgeRoot.resolve("ops")).name());
    assertEquals("faq", KnowledgeManifest.read(knowledgeRoot.resolve("faq")).name());
    assertTrue(Files.isRegularFile(knowledgeRoot.resolve("ops/disk.md")));
    assertTrue(Files.isRegularFile(knowledgeRoot.resolve("faq/faq.md")));
    assertTrue(store.allDocuments(".").isEmpty(), "「.」不得留下索引记录");
  }

  @Test
  @DisplayName("库目录本身是指向根外的软链：操作入口拒绝，库外文件不入索引、也不被删除")
  void symlinkedBaseDirectoryIsRejected() throws IOException {
    Path outside = Files.createDirectories(workspace.resolve("outside-kb"));
    Files.writeString(
        outside.resolve("KNOWLEDGE.md"), "---\nname: escape\ndescription: 越界库\n---\n");
    Files.writeString(outside.resolve("secret.md"), "# 外部机密\n\n" + ESCAPE_SENTINEL + " 内容。\n");
    SymlinkAssumptions.createSymbolicLinkOrAssume(
        knowledgeRoot.resolve("escape"), Path.of("..", "outside-kb"));

    for (Runnable call :
        List.<Runnable>of(
            () -> backend.importDocument("escape", "secret.md"),
            () -> backend.rebuild("escape"),
            () -> backend.updateBase("escape", "x"),
            () -> backend.deleteBase("escape"))) {
      IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, call::run);
      assertTrue(
          rejected.getMessage().contains("越界"), "名字合法但真实路径越界，必须按边界臂拒绝: " + rejected.getMessage());
    }

    runBackground();
    assertTrue(store.allDocuments("escape").isEmpty(), "软链目标的文档不得入库");
    assertTrue(
        backend.retrieve(new KnowledgeQuery(ESCAPE_SENTINEL, 5, List.of("escape"))).stream()
            .noneMatch(hit -> hit.content().contains(ESCAPE_SENTINEL)),
        "库外内容不得被检索命中");
    // deleteBase 不得顺着软链删到根外
    assertTrue(Files.isRegularFile(outside.resolve("secret.md")));
    assertTrue(Files.isRegularFile(outside.resolve("KNOWLEDGE.md")));
  }

  @Test
  @DisplayName("根内软链别名 alias -> ops：后端各入口拒绝，ops 的清单/文档/索引行原样保留")
  void symlinkAliasInsideRootIsRejectedAndTargetSurvives() throws IOException {
    backend.importDocument("ops", "disk.md");
    runBackground();
    assertEquals(DocumentState.READY, backend.status("ops").get(0).state(), "前提：目标库已有索引");
    SymlinkAssumptions.createSymbolicLinkOrAssume(knowledgeRoot.resolve("alias"), Path.of("ops"));

    for (Runnable call :
        List.<Runnable>of(
            () -> backend.createBase("alias", "x"),
            () -> backend.updateBase("alias", "改描述"),
            () -> backend.deleteBase("alias"),
            () -> backend.importDocument("alias", "disk.md"),
            () -> backend.rebuild("alias"))) {
      IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, call::run);
      assertTrue(
          rejected.getMessage().contains("软链别名"),
          "根内别名必须按「名字与外层目录不一致」拒绝: " + rejected.getMessage());
    }

    // 别名这个请求不得动到目标库：清单、文档、索引行都还在（删除别名时 DB 删的是 alias 行，
    // 随后 walk 真实 ops 目录删文件——DB 与磁盘会直接分叉）
    assertEquals(
        "运维手册", KnowledgeManifest.read(knowledgeRoot.resolve("ops")).description(), "目标库清单不得被改写");
    assertTrue(Files.isRegularFile(knowledgeRoot.resolve("ops/disk.md")), "目标库文档不得被删");
    assertTrue(Files.isSymbolicLink(knowledgeRoot.resolve("alias")), "别名本身不得被删");
    assertEquals(DocumentState.READY, backend.status("ops").get(0).state(), "目标库索引不得被清");
    assertTrue(store.allDocuments("alias").isEmpty(), "别名不得留下索引记录");
  }

  @Test
  @DisplayName("非 SAFE 字符集统一拒绝（中文 / 空格 / 带点）")
  void nonSafeNamesAreRejected() {
    for (String name : List.of("团队知识", "my kb", "v1.2")) {
      assertThrows(IllegalArgumentException.class, () -> backend.importDocument(name, "secret.md"));
      assertThrows(IllegalArgumentException.class, () -> backend.rebuild(name));
      assertThrows(IllegalArgumentException.class, () -> backend.createBase(name, "x"));
    }
  }

  @Test
  @DisplayName("合法库名照常：建库 → 导入 → 重建 → 检索")
  void safeBaseStillWorks() {
    backend.importDocument("ops", "disk.md");
    runBackground();
    DocumentStatus status = backend.status("ops").get(0);
    assertEquals(DocumentState.READY, status.state(), "合法库的导入照常进入终态");
    assertFalse(retrieve("inode", "ops").isEmpty(), "重建前可检索");

    backend.rebuild("ops");

    assertFalse(retrieve("inode", "ops").isEmpty(), "重建后可检索");
  }

  private List<KnowledgeHit> retrieve(String query, String... kbNames) {
    return backend.retrieve(new KnowledgeQuery(query, 10, List.of(kbNames)));
  }

  private void runBackground() {
    List<Runnable> tasks = new ArrayList<>(backgroundTasks);
    backgroundTasks.clear();
    tasks.forEach(Runnable::run);
  }

  /** 确定性测试向量器：字符直方图 + 归一化。 */
  private static final class TestEmbedder implements TextEmbedder {

    private static final int DIM = 16;

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
      return "test/v1";
    }

    @Override
    public int dimensions() {
      return DIM;
    }
  }
}
