package io.oryxos.core.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.knowledge.KnowledgeBindingService;
import io.oryxos.core.knowledge.KnowledgeWorkspaceFixture;
import io.oryxos.core.profile.Profile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T024：渐进披露注入（FR-005）——每轮只注入绑定库元数据与检索指引，零绑定零注入、正文永不预载。 */
class ContextLoaderKnowledgeTest {

  @TempDir Path root;

  private KnowledgeBindingService bindings;
  private ContextLoader loader;

  @BeforeEach
  void setUp() {
    KnowledgeWorkspaceFixture.workspace(root);
    KnowledgeWorkspaceFixture.knowledgeBase(
        root, "ops-manual", "运维手册", Map.of("disk.md", "# 磁盘告警\n\n处置步骤，正文不得出现在 prompt"));
    KnowledgeWorkspaceFixture.agent(root, "assistant");
    bindings = new KnowledgeBindingService(root);
    loader = new ContextLoader(root, null, bindings);
  }

  @Test
  void injectsOnlyMetadataAndGuidanceForBoundBases() {
    bindings.bind("assistant", "ops-manual");

    String context = loader.load(profile(List.of("retrieve_knowledge", "read_file")));

    assertTrue(context.contains("ops-manual"), "注入绑定库名称");
    assertTrue(context.contains("运维手册"), "注入绑定库描述");
    assertTrue(context.contains("retrieve_knowledge"), "注入检索指引");
    // 正文永不预载（FR-005）
    assertFalse(context.contains("处置步骤"), "文档正文绝不进入 system prompt");
  }

  @Test
  void zeroBindingMeansZeroInjection() {
    String context = loader.load(profile(List.of("retrieve_knowledge")));
    assertFalse(context.contains("知识库"), "未绑定 Agent 上下文零知识库注入（SC-005）");
  }

  @Test
  void noInjectionWhenToolNotDeclared() {
    bindings.bind("assistant", "ops-manual");
    String context = loader.load(profile(List.of("read_file")));
    assertFalse(context.contains("ops-manual"), "未声明检索工具的 Agent 不注入（注入了也无法使用）");
  }

  @Test
  void brokenBindingIsSkippedWithoutFailingPromptAssembly() throws Exception {
    bindings.bind("assistant", "ops-manual");
    // 制造 dangling：目标库目录被移走
    Path kbDir = root.resolve("knowledge/ops-manual");
    Path moved = root.resolve("moved-away");
    Files.move(kbDir, moved);

    String context = loader.load(profile(List.of("retrieve_knowledge")));

    assertFalse(context.contains("ops-manual"), "损坏绑定跳过注入，不炸 prompt 组装");
  }

  private static Profile profile(List<String> tools) {
    return new Profile(
        "assistant",
        null,
        null,
        new Profile.ProviderRef("mock", "mock", null),
        tools,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }
}
