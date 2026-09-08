package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.knowledge.KnowledgeAdmin;
import io.oryxos.core.knowledge.KnowledgeBackendRegistry;
import io.oryxos.core.knowledge.KnowledgeBindingService;
import io.oryxos.core.knowledge.KnowledgeReferencedException;
import io.oryxos.core.knowledge.KnowledgeService;
import io.oryxos.core.knowledge.model.DocumentState;
import io.oryxos.core.knowledge.model.KnowledgeHit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * T047（US6 / SC-004）：无 key 环境整机走通「建库 → 导入 → 索引就绪 → 绑定 → 检索命中带出处 → 原文可跟读 → 对话不受影响 → 审计落库 → 引用保护 →
 * 删除」，且相同输入恒得相同结果（mock 确定性 向量，CI 可稳定断言）。因整机上下文较重，打 {@code @Tag("integration")} 默认被 gate 排除。
 * 手动跑：{@code mvn -pl oryxos-boot test -Dgroups=integration -DexcludedGroups=
 * -Dtest=KnowledgeFlowIT}。
 */
@Tag("integration")
class KnowledgeFlowIT {

  @Test
  @DisplayName("mock 全链路：建库到删除的完整闭环，结果确定可重复")
  void endToEndKnowledgeFlowWithMockProvider() throws Exception {
    Path root = seedWorkspace();
    String dbUrl = "jdbc:sqlite:" + root.resolve("knowledge-it.db");
    try (ConfigurableApplicationContext ctx = boot(root, dbUrl)) {
      KnowledgeAdmin admin =
          ctx.getBean(KnowledgeBackendRegistry.class).localDefault().admin().orElseThrow();
      KnowledgeBindingService bindings = ctx.getBean(KnowledgeBindingService.class);
      KnowledgeService knowledge = ctx.getBean(KnowledgeService.class);

      // 建库 + 落盘文档 + 导入（两段式后台推进，轮询状态机）
      admin.createBase("ops-manual", "运维手册");
      Files.writeString(
          root.resolve("knowledge/ops-manual/disk-alert.md"), "# 磁盘告警处置\n\n先查 inode 占用，再清理过期日志。");
      admin.importDocument("ops-manual", "disk-alert.md");
      awaitReady(admin, "ops-manual");

      // 绑定（软连接唯一真相源）→ 检索命中带完整出处（SC-003）
      bindings.bind("kb-agent", "ops-manual");
      List<KnowledgeHit> hits = knowledge.retrieveForAgent("kb-agent", "磁盘告警", 5, null);
      assertFalse(hits.isEmpty());
      assertEquals("ops-manual", hits.get(0).citation().kbName());
      assertEquals("disk-alert.md", hits.get(0).citation().relPath());
      assertTrue(hits.get(0).citation().readable());
      assertFalse(hits.get(0).degraded(), "mock 向量在场不降级");
      // 本地出处可跟读（SC-003）：出处路径真实存在
      assertTrue(Files.exists(root.resolve("knowledge/ops-manual/disk-alert.md")));

      // 确定性（SC-004）：相同查询恒得相同排序
      List<String> first = hits.stream().map(h -> h.citation().display()).toList();
      List<String> second =
          knowledge.retrieveForAgent("kb-agent", "磁盘告警", 5, null).stream()
              .map(h -> h.citation().display())
              .toList();
      assertEquals(first, second);

      // 零绑定可读错误（SC-005）
      IllegalArgumentException zeroBinding =
          assertThrows(
              IllegalArgumentException.class,
              () -> knowledge.retrieveForAgent("lonely-agent", "磁盘", 5, null));
      assertTrue(zeroBinding.getMessage().contains("未绑定"));

      // 对话链路不受影响（mock ReAct 全链路 + 审计）
      String reply = ctx.getBean(AgentService.class).processStateless("kb-agent", "记录一次巡检");
      assertNotNull(reply);

      // 引用保护（FR-011）→ 解绑后可删，删干净
      assertThrows(
          KnowledgeReferencedException.class, () -> bindings.ensureDeletable("ops-manual"));
      bindings.unbind("kb-agent", "ops-manual");
      bindings.ensureDeletable("ops-manual");
      admin.deleteBase("ops-manual");
      assertFalse(Files.exists(root.resolve("knowledge/ops-manual")));
    }
  }

  private static void awaitReady(KnowledgeAdmin admin, String kbName) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      var statuses = admin.status(kbName);
      if (!statuses.isEmpty() && statuses.get(0).state() == DocumentState.READY) {
        return;
      }
      if (!statuses.isEmpty() && statuses.get(0).state() == DocumentState.FAILED) {
        throw new AssertionError("索引失败: " + statuses.get(0).failureReason());
      }
      Thread.sleep(100);
    }
    throw new AssertionError("索引 10 秒内未就绪");
  }

  private static ConfigurableApplicationContext boot(Path root, String dbUrl) {
    return new SpringApplicationBuilder(OryxOsRuntime.class)
        .run(
            "--oryxos.root=" + root,
            "--oryxos.providers[0].name=mock",
            "--knowledge.embedding.provider=mock",
            "--spring.datasource.url=" + dbUrl,
            "--spring.lifecycle.timeout-per-shutdown-phase=100ms",
            "--spring.main.web-application-type=none");
  }

  private static Path seedWorkspace() throws IOException {
    Path root = Files.createTempDirectory("oryxos-knowledge-it");
    Files.createDirectories(root.resolve("memory"));
    Files.createDirectories(root.resolve("knowledge"));
    agent(root, "kb-agent", "save_memory", "retrieve_knowledge", "read_file");
    agent(root, "lonely-agent", "save_memory", "retrieve_knowledge");
    return root;
  }

  private static void agent(Path root, String name, String... tools) throws IOException {
    StringBuilder toolList = new StringBuilder();
    for (String tool : tools) {
      toolList.append("  - ").append(tool).append('\n');
    }
    Files.createDirectories(root.resolve("agents").resolve(name));
    Files.writeString(
        root.resolve("agents").resolve(name).resolve("AGENT.md"),
        """
        ---
        name: %s
        description: 知识库整机自测 Agent
        identity:
          agent_name: 测试员
          prompt: 你是知识库自测助手。
        provider:
          name: mock
          model: mock-model
        tools:
        %ssettings:
          max_iterations: 5
          max_history_turns: 10
        ---
        被触发时按指令执行。
        """
            .formatted(name, toolList));
  }
}
