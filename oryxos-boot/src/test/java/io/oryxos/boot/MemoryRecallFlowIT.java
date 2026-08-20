package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.agent.ToolExecutionContext;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.memory.MemoryRecallEngine;
import io.oryxos.memory.MemoryServiceImpl;
import io.oryxos.storage.MemoryVectorEntity;
import io.oryxos.storage.MemoryVectorRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 015 T026（US4 / SC-005）：mock 整机走通「save(core+archival) → 异步索引落表 → 三路 recall 确定性复检 → 对账补齐 → agent
 * 隔离」，以及配置态降级演练（embedding 配置指向不存在的 provider → 关键词+时间两路 + 尾行标注、 写入零丢失、启动不中断）。整机上下文较重，打
 * {@code @Tag("integration")} 默认被 gate 排除。 手动跑：{@code mvn -pl oryxos-boot test -Dgroups=integration
 * -DexcludedGroups= -Dtest=MemoryRecallFlowIT}。
 */
@Tag("integration")
class MemoryRecallFlowIT {

  @Test
  @DisplayName("mock 全链路：三路检索确定可重复、索引对账补齐、agent 隔离")
  void endToEndRecallWithMockEmbedding() throws Exception {
    Path root = Files.createTempDirectory("oryxos-memory-it");
    String dbUrl = "jdbc:sqlite:" + root.resolve("oryxos.db");
    try (ConfigurableApplicationContext ctx = boot(root, dbUrl, "mock")) {
      MemoryServiceImpl memory = ctx.getBean(MemoryServiceImpl.class);
      MemoryVectorRepository vectors = ctx.getBean(MemoryVectorRepository.class);
      ToolExecutionContext.setAgentName("mem-agent");
      try {
        memory.remember("用户叫小林，永远用中文回复", MemoryScope.CORE);
        memory.remember("发布流程在灰度环节踩雷，回滚后改为分批放量", MemoryScope.ARCHIVAL);
        memory.remember("工单 OPS-4721 已升级到二线", MemoryScope.ARCHIVAL);
        memory.remember("例行巡检无异常", MemoryScope.ARCHIVAL);

        awaitVectorRows(vectors, "mem-agent", 3); // 仅归档入索引（core 不入，FR-005）

        List<String> first = memory.recall("OPS-4721");
        List<String> second = memory.recall("OPS-4721");
        assertEquals(first, second, "mock 确定性：同输入恒同输出（SC-005）");
        assertTrue(first.stream().anyMatch(l -> l.contains("OPS-4721")), "关键词命中在场");
        assertFalse(first.contains(MemoryRecallEngine.DEGRADE_NOTICE), "mock 向量在场不降级");
        assertTrue(first.stream().noneMatch(l -> l.contains("小林")), "core 条目绝不进检索结果");

        // 对账补齐（FR-007）：人为删一行索引 → reconcile → 恢复
        List<MemoryVectorEntity> rows = vectors.findByAgentName("mem-agent");
        vectors.deleteById(rows.get(0).getId());
        assertEquals(2, vectors.findByAgentName("mem-agent").size());
        memory.reconcileIndex("mem-agent");
        awaitVectorRows(vectors, "mem-agent", 3);

        // agent 隔离（US1 场景 6）
        ToolExecutionContext.setAgentName("other-agent");
        assertTrue(memory.recall("OPS-4721").isEmpty(), "B 检索不到 A 的记忆");
      } finally {
        ToolExecutionContext.clear();
      }
    }
  }

  @Test
  @DisplayName("配置态降级：embedding 指向不存在的 provider_两路返回 + 尾行标注_写入零丢失")
  void configuredButBrokenEmbeddingDegradesReadably() throws Exception {
    Path root = Files.createTempDirectory("oryxos-memory-it-degrade");
    String dbUrl = "jdbc:sqlite:" + root.resolve("oryxos.db");
    try (ConfigurableApplicationContext ctx = boot(root, dbUrl, "ghost-provider")) {
      MemoryServiceImpl memory = ctx.getBean(MemoryServiceImpl.class);
      ToolExecutionContext.setAgentName("mem-agent");
      try {
        memory.remember("降级期也必须落库的条目 needle", MemoryScope.ARCHIVAL); // 零丢失（FR-005）

        List<String> lines = memory.recall("needle");

        assertEquals(
            MemoryRecallEngine.DEGRADE_NOTICE, lines.getLast(), "已配置但语义路故障 → 尾行标注（FR-003）");
        assertTrue(lines.stream().anyMatch(l -> l.contains("needle")), "关键词 + 时间两路照常返回");
      } finally {
        ToolExecutionContext.clear();
      }
    }
  }

  private static void awaitVectorRows(MemoryVectorRepository vectors, String agent, int expected)
      throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      if (vectors.findByAgentName(agent).size() == expected) {
        return;
      }
      Thread.sleep(100);
    }
    assertEquals(expected, vectors.findByAgentName(agent).size(), "异步向量化 10 秒内应落表");
  }

  private static ConfigurableApplicationContext boot(
      Path root, String dbUrl, String embeddingProvider) {
    return new SpringApplicationBuilder(OryxOsRuntime.class)
        .run(
            "--oryxos.root=" + root,
            "--oryxos.providers[0].name=mock",
            "--embedding.provider=" + embeddingProvider, // 015 全局键（旧键 knowledge.embedding.* 兼容）
            "--spring.datasource.url=" + dbUrl,
            "--spring.lifecycle.timeout-per-shutdown-phase=100ms",
            "--spring.main.web-application-type=none");
  }
}
