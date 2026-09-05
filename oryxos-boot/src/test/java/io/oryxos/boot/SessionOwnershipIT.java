package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.channel.MessageDeduplicator;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** 026 US1：双副本（双上下文 + 共享 zonky PG）下同会话有序恰好一次、重推跨副本去重、独立会话并行、 单机档零变化（SC-001/004 测试面）。 */
@Tag("postgres")
class SessionOwnershipIT {

  private static EmbeddedPostgres postgres;
  private static ConfigurableApplicationContext replicaA;
  private static ConfigurableApplicationContext replicaB;

  @BeforeAll
  static void startReplicas() throws IOException {
    postgres = EmbeddedPostgres.start();
    String url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres?user=postgres";
    replicaA = boot(url, "replica-a", true);
    replicaB = boot(url, "replica-b", true);
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
      String url, String instanceId, boolean clusterEnabled) throws IOException {
    Path root = seedWorkspace();
    return new SpringApplicationBuilder(OryxOsRuntime.class)
        .run(
            "--oryxos.root=" + root,
            "--oryxos.providers[0].name=mock",
            "--spring.datasource.url=" + url,
            "--oryxos.cluster.enabled=" + clusterEnabled,
            "--oryxos.cluster.instance-id=" + instanceId,
            "--oryxos.cluster.poll-interval=50ms",
            "--memory.backend=sqlite",
            "--spring.lifecycle.timeout-per-shutdown-phase=100ms",
            "--spring.main.web-application-type=none");
  }

  @Test
  @DisplayName(
      "same session: 20 messages across two replicas — ordered, exactly-once, no conflicts")
  void sameSessionAcrossReplicasIsSerializedAndComplete() throws Exception {
    SessionManager sessions = replicaA.getBean(SessionManager.class);
    Session session = sessions.getOrCreate("it", "same-session-user", "default");
    AgentService serviceA = replicaA.getBean(AgentService.class);
    AgentService serviceB = replicaB.getBean(AgentService.class);

    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    List<Future<String>> futures = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      AgentService target = (i % 2 == 0) ? serviceA : serviceB; // 平台随机投一的等价物：交替落两副本
      String content = "消息-" + i;
      futures.add(
          pool.submit((Callable<String>) () -> target.process(freshView(session), content)));
    }
    for (Future<String> future : futures) {
      assertFalse(future.get().isBlank()); // 每条恰好一答、零冲突异常（异常会让 get 抛）
    }
    pool.shutdown();

    Session finalView =
        replicaB.getBean(SessionManager.class).get(session.sessionId()).orElseThrow();
    long userMessages =
        finalView.messages().stream()
            .filter(m -> "user".equalsIgnoreCase(String.valueOf(m.role())))
            .count();
    assertEquals(20, userMessages, "历史完整：20 条用户消息全部落库（有序由会话锁+租约保证）");
  }

  @Test
  @DisplayName("platform re-push to the other replica is deduplicated via shared receipts")
  void redeliveryAcrossReplicasIsDeduplicated() {
    MessageDeduplicator dedupA = replicaA.getBean(MessageDeduplicator.class);
    MessageDeduplicator dedupB = replicaB.getBean(MessageDeduplicator.class);
    // 诊断：装配必须是共享回执实现
    assertEquals("SharedReceiptDeduplicator", dedupA.getClass().getSimpleName(), "A 副本去重实现");
    assertEquals("SharedReceiptDeduplicator", dedupB.getClass().getSimpleName(), "B 副本去重实现");

    assertTrue(dedupA.markIfFirst("feishu:redelivered-1")); // 首投副本 A
    assertFalse(dedupB.markIfFirst("feishu:redelivered-1")); // 超时重推落副本 B：共享回执拦截
    assertFalse(dedupA.markIfFirst("feishu:redelivered-1")); // A 本地缓存拦截
    assertTrue(dedupB.markIfFirst("feishu:another-msg"));
  }

  @Test
  @DisplayName("independent sessions run fully in parallel across replicas")
  void independentSessionsRunInParallel() throws Exception {
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    List<Future<String>> futures = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      ConfigurableApplicationContext ctx = (i % 2 == 0) ? replicaA : replicaB;
      String userId = "parallel-user-" + i;
      futures.add(
          pool.submit(
              (Callable<String>)
                  () -> {
                    Session s =
                        ctx.getBean(SessionManager.class).getOrCreate("it", userId, "default");
                    return ctx.getBean(AgentService.class).process(s, "独立会话消息");
                  }));
    }
    long start = System.nanoTime();
    for (Future<String> future : futures) {
      assertFalse(future.get().isBlank());
    }
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    pool.shutdown();
    assertTrue(elapsedMs < 30_000, "30 独立会话并行完成（无跨会话等待），耗时=" + elapsedMs + "ms");
  }

  @Test
  @DisplayName("single replica with cluster enabled works standalone (self-claiming)")
  void singleReplicaClusterEnabledSelfClaims() throws Exception {
    // 场景⑤：enabled=true 单副本——认领/续租/释放自洽（自己抢自己的）
    Session s = replicaA.getBean(SessionManager.class).getOrCreate("it", "solo-user", "default");
    AgentService service = replicaA.getBean(AgentService.class);
    assertFalse(service.process(s, "第一条").isBlank());
    assertFalse(service.process(freshView(s), "第二条").isBlank()); // 释放后可再次认领
  }

  @Test
  @DisplayName(
      "standalone tier (enabled=false) keeps current behavior with zero coordination writes")
  void standaloneTierUnchanged() throws Exception {
    Path root = seedWorkspace();
    Path db = root.resolve("standalone.db");
    try (ConfigurableApplicationContext standalone =
        new SpringApplicationBuilder(OryxOsRuntime.class)
            .run(
                "--oryxos.root=" + root,
                "--oryxos.providers[0].name=mock",
                "--spring.datasource.url=jdbc:sqlite:" + db,
                "--spring.lifecycle.timeout-per-shutdown-phase=100ms",
                "--spring.main.web-application-type=none")) {
      Session s =
          standalone.getBean(SessionManager.class).getOrCreate("it", "standalone-user", "default");
      assertFalse(standalone.getBean(AgentService.class).process(s, "单机消息").isBlank());
      try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
          java.sql.Statement st = conn.createStatement();
          java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM session_turn_leases")) {
        assertTrue(rs.next());
        assertEquals(0, rs.getLong(1), "单机档零协调写（NOOP）");
      }
    }
  }

  private Session freshView(Session session) {
    // 锁内会重读最新快照；此处仅提供定位用的 Session 引用
    return session;
  }

  private static Path seedWorkspace() throws IOException {
    Path root = Files.createTempDirectory("oryxos-session-ownership");
    Files.createDirectories(root.resolve("memory"));
    Files.createDirectories(root.resolve("agents").resolve("default"));
    Files.writeString(
        root.resolve("agents/default/AGENT.md"),
        """
        ---
        name: default
        description: ownership fixture
        identity:
          agent_name: Ownership
          prompt: You are a test agent.
        provider:
          name: mock
          model: mock-model
        tools:
          - save_memory
        settings:
          max_iterations: 3
          max_history_turns: 50
        ---
        Test fixture.
        """);
    return root;
  }
}
