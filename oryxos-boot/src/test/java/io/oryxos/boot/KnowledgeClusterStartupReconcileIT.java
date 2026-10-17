package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.knowledge.KnowledgeAdmin;
import io.oryxos.core.knowledge.KnowledgeBackendRegistry;
import io.oryxos.core.knowledge.model.DocumentState;
import io.oryxos.core.knowledge.model.DocumentStatus;
import io.oryxos.knowledge.index.KnowledgeIndexService;
import io.oryxos.knowledge.store.ChunkStore;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * FR-010 集群档启动对账：集群档不装配 {@code KnowledgeWatcher}（NFS 上 inotify 不可靠），启动必须另有入口把重启 遗留的
 * PENDING/INDEXING 拉回终态。否则那行没有任何在飞任务，文件在盘上、内容可读却检索不到，且 failureReason 为 null——管理员只能整库 rebuild。
 *
 * <p>触发点是 {@code ApplicationRunner}：它在 context refresh 之后执行，会把 {@code ApplicationReadyEvent}
 * 推迟一个扫描的时间， 但不阻塞监听（端口在 refresh 阶段已经起来）。所以这次扫描只决定「多久之后 PENDING 会自愈」，不是启动阻塞项。
 */
@Tag("postgres")
class KnowledgeClusterStartupReconcileIT {

  private static EmbeddedPostgres postgres;
  private static String url;
  private static Path root;

  @BeforeAll
  static void startDatabase() throws IOException {
    postgres = EmbeddedPostgres.start();
    url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres?user=postgres";
    root = seedWorkspace();
  }

  @AfterAll
  static void stopDatabase() throws IOException {
    if (postgres != null) {
      postgres.close();
    }
  }

  @Test
  @DisplayName("重启把 PENDING 文档留给集群副本：新副本启动对账必须把它重新驱动到就绪")
  void clusterRestartReDrivesADocumentLeftPending() throws Exception {
    String kb = "kb-restart";
    try (ConfigurableApplicationContext first = boot("replica-a")) {
      KnowledgeAdmin admin = admin(first);
      admin.createBase(kb, "重启对账");
      Files.writeString(root.resolve("knowledge/" + kb + "/doc.md"), "# 文档\n\n重启前被杀的内容。");

      // 模拟「同步段写完 PENDING、后台段之前进程被杀」：直接落一条真实指纹的 PENDING 行，
      // 没有在飞任务，也没有失败原因。
      ChunkStore store = first.getBean(ChunkStore.class);
      long generation = first.getBean(KnowledgeIndexService.class).activeGeneration(kb);
      store.saveDocument(
          new ChunkStore.DocumentRecord(
              null,
              kb,
              "doc.md",
              sha256(root.resolve("knowledge/" + kb + "/doc.md")),
              DocumentState.PENDING,
              null,
              0,
              generation,
              null));
      assertEquals(
          DocumentState.PENDING, admin(first).status(kb).get(0).state(), "前提：重启前停在 PENDING");
    }

    // 重启：同一共享 PG、同一工作区，新副本。集群档没有 watcher，启动对账是唯一收敛机会。
    try (ConfigurableApplicationContext restarted = boot("replica-b")) {
      KnowledgeAdmin admin = admin(restarted);
      awaitReady(admin, kb);
      List<DocumentStatus> statuses = admin.status(kb);
      assertEquals(1, statuses.size(), "启动对账不得复制文档行");
      assertEquals(DocumentState.READY, statuses.get(0).state(), "重启遗留的 PENDING 必须收敛到就绪");
    }
  }

  private static ConfigurableApplicationContext boot(String instanceId) {
    return new SpringApplicationBuilder(OryxOsRuntime.class)
        .run(
            "--oryxos.root=" + root,
            "--oryxos.providers[0].name=mock",
            "--embedding.provider=mock",
            "--spring.datasource.url=" + url,
            "--oryxos.cluster.enabled=true",
            "--oryxos.cluster.instance-id=" + instanceId,
            "--oryxos.cluster.workspace-poll-interval=100ms",
            "--oryxos.cluster.lease-ttl=2s",
            "--oryxos.cluster.wait-timeout=10s",
            "--memory.backend=sqlite",
            "--spring.lifecycle.timeout-per-shutdown-phase=100ms",
            "--spring.main.web-application-type=none");
  }

  private static KnowledgeAdmin admin(ConfigurableApplicationContext ctx) {
    return ctx.getBean(KnowledgeBackendRegistry.class).localDefault().admin().orElseThrow();
  }

  private static void awaitReady(KnowledgeAdmin admin, String kbName) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      List<DocumentStatus> statuses = admin.status(kbName);
      if (!statuses.isEmpty()
          && statuses.stream().allMatch(s -> s.state() == DocumentState.READY)) {
        return;
      }
      if (statuses.stream().anyMatch(s -> s.state() == DocumentState.FAILED)) {
        throw new AssertionError(
            "索引失败: "
                + statuses.stream()
                    .filter(s -> s.state() == DocumentState.FAILED)
                    .findFirst()
                    .map(DocumentStatus::failureReason)
                    .orElse(""));
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "10 秒内未就绪：重启遗留的 PENDING 没有被启动对账重新驱动（集群档没有 watcher，" + "手动刷新也把 PENDING 当作非差异）");
  }

  private static String sha256(Path file) throws IOException {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
    }
  }

  private static Path seedWorkspace() throws IOException {
    Path workspace = Files.createTempDirectory("oryxos-kb-cluster-reconcile");
    Files.createDirectories(workspace.resolve("memory"));
    Files.createDirectories(workspace.resolve("agents").resolve("default"));
    Files.writeString(
        workspace.resolve("agents/default/AGENT.md"),
        """
        ---
        name: default
        description: kb fixture
        identity:
          agent_name: KbFixture
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
    return workspace;
  }
}
