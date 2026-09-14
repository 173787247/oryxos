package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.cluster.WorkspaceVersionNotifier;
import io.oryxos.core.cluster.WorkspaceVersionPoller;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.skill.SkillRegistry;
import io.oryxos.core.skill.SkillService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 027 US1（SC-001）：双副本共享工作区（同一临时目录模拟共享卷）+ 共享 zonky PG—— A 副本管理写操作，B 副本经版本号轮询秒级可见；装配档位正确（集群档 poller、无
 * watcher）； 单机档 NOOP 通知零协调写。
 */
@Tag("postgres")
class FilePlaneVisibilityIT {

  private static EmbeddedPostgres postgres;
  private static Path sharedRoot;
  private static ConfigurableApplicationContext replicaA;
  private static ConfigurableApplicationContext replicaB;

  @BeforeAll
  static void startReplicas() throws IOException {
    postgres = EmbeddedPostgres.start();
    String url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres?user=postgres";
    sharedRoot = seedWorkspace();
    replicaA = boot(url, sharedRoot, "walk-a", true);
    replicaB = boot(url, sharedRoot, "walk-b", true);
  }

  @AfterAll
  static void stopReplicas() throws IOException {
    if (replicaA != null) {
      replicaA.close();
    }
    if (replicaB != null) {
      replicaB.close();
    }
    if (postgres != null) {
      postgres.close();
    }
  }

  private static ConfigurableApplicationContext boot(
      String url, Path root, String instanceId, boolean clusterEnabled) {
    return new SpringApplicationBuilder(OryxOsRuntime.class)
        .run(
            "--oryxos.root=" + root,
            "--oryxos.providers[0].name=mock",
            "--spring.datasource.url=" + url,
            "--oryxos.cluster.enabled=" + clusterEnabled,
            "--oryxos.cluster.instance-id=" + instanceId,
            "--oryxos.cluster.workspace-poll-interval=100ms",
            "--memory.backend=sqlite",
            "--spring.lifecycle.timeout-per-shutdown-phase=100ms",
            "--spring.main.web-application-type=none");
  }

  @Test
  @DisplayName("cluster wiring: poller on, watchers off; standalone wiring is the inverse")
  void clusterWiringSwapsWatcherForPoller() throws IOException {
    assertNotNull(replicaA.getBean(WorkspaceVersionPoller.class), "集群档装 poller");
    assertEquals(
        0,
        replicaA.getBeanNamesForType(io.oryxos.core.agent.WorkspaceWatcher.class).length,
        "集群档不装 agents watcher（NFS 上 inotify 不可靠）");
    assertEquals(
        0,
        replicaA.getBeanNamesForType(io.oryxos.knowledge.watch.KnowledgeWatcher.class).length,
        "集群档不装 knowledge watcher");

    Path standaloneRoot = seedWorkspace();
    String sqliteUrl = "jdbc:sqlite:" + standaloneRoot.resolve("standalone.db");
    try (ConfigurableApplicationContext standalone =
        boot(sqliteUrl, standaloneRoot, "solo", false)) {
      assertNotNull(
          standalone.getBean(io.oryxos.core.agent.WorkspaceWatcher.class), "单机档 watcher 原样");
      assertEquals(
          0, standalone.getBeanNamesForType(WorkspaceVersionPoller.class).length, "单机档不轮询（FR-010）");
      assertSame(
          WorkspaceVersionNotifier.NOOP,
          standalone.getBean(WorkspaceVersionNotifier.class),
          "单机档通知为 NOOP：零版本号写入（FR-010）");
    }
  }

  @Test
  @DisplayName("agent created via A becomes visible on B within 3s (SC-001)")
  void agentCreatedOnAVisibleOnB() {
    replicaA.getBean(AgentLifecycleService.class).create("cross-agent", "A 建 B 见");
    awaitWithin3s(
        () -> replicaB.getBean(ProfileRegistry.class).exists("cross-agent"),
        "B 副本 3 秒内可见 A 建的 Agent");
    assertEquals(
        "A 建 B 见",
        replicaB.getBean(ProfileRegistry.class).get("cross-agent").orElseThrow().description());
  }

  @Test
  @DisplayName("agent config updated via A takes effect on B within 3s")
  void agentUpdatedOnAEffectiveOnB() {
    replicaA.getBean(AgentLifecycleService.class).create("cross-update", "初始描述");
    awaitWithin3s(
        () -> replicaB.getBean(ProfileRegistry.class).exists("cross-update"), "B 可见新 Agent");
    replicaA
        .getBean(AgentLifecycleService.class)
        .updateBasicInfo("cross-update", "A 改 B 生效", null, null);
    awaitWithin3s(
        () ->
            replicaB
                .getBean(ProfileRegistry.class)
                .get("cross-update")
                .map(p -> "A 改 B 生效".equals(p.description()))
                .orElse(false),
        "B 副本 3 秒内以新配置可见");
  }

  @Test
  @DisplayName("agent deleted via A disappears on B within 3s")
  void agentDeletedOnAGoneOnB() {
    replicaA.getBean(AgentLifecycleService.class).create("cross-delete", "待删");
    awaitWithin3s(
        () -> replicaB.getBean(ProfileRegistry.class).exists("cross-delete"), "B 先可见再验证删除");
    replicaA.getBean(AgentLifecycleService.class).delete("cross-delete");
    awaitWithin3s(
        () -> !replicaB.getBean(ProfileRegistry.class).exists("cross-delete"),
        "B 副本 3 秒内该 Agent 消失");
  }

  @Test
  @DisplayName("skill created via A becomes visible on B within 3s")
  void skillCreatedOnAVisibleOnB() {
    replicaA.getBean(SkillService.class).create("cross-skill", "A 建 B 见的技能", "## 用法\n照做即可");
    awaitWithin3s(
        () -> replicaB.getBean(SkillRegistry.class).exists("cross-skill"),
        "B 副本 3 秒内可见 A 建的 Skill");
  }

  @Test
  @DisplayName("replica restart rebuilds registry from current shared disk (US1 场景 5)")
  void restartedReplicaSeesCurrentDisk() throws IOException {
    replicaA.getBean(AgentLifecycleService.class).create("pre-restart-agent", "重启前建");
    String url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres?user=postgres";
    try (ConfigurableApplicationContext fresh = boot(url, sharedRoot, "walk-c", true)) {
      // 不依赖错过的事件：启动扫描按当前盘面重建
      assertTrue(fresh.getBean(ProfileRegistry.class).exists("pre-restart-agent"));
      assertTrue(fresh.getBean(ProfileRegistry.class).exists("default"));
    }
  }

  private static void awaitWithin3s(BooleanSupplier condition, String message) {
    long deadline = System.nanoTime() + 3_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertFalse(true, message + "（3 秒内未达成）");
  }

  private static Path seedWorkspace() throws IOException {
    Path root = Files.createTempDirectory("oryxos-file-plane");
    Files.createDirectories(root.resolve("memory"));
    Files.createDirectories(root.resolve("agents").resolve("default"));
    Files.writeString(
        root.resolve("agents/default/AGENT.md"),
        """
        ---
        name: default
        description: file plane fixture
        identity:
          agent_name: FilePlane
          prompt: You are a test agent.
        provider:
          name: mock
          model: mock-model
        settings:
          max_iterations: 3
          max_history_turns: 50
        ---
        Test fixture.
        """);
    return root;
  }
}
