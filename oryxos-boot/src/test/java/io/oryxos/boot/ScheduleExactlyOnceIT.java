package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.agent.AgentScheduler;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 026 US2：双副本下定时任务恰好一次（SC-002）——两副本调度器同 cron 到点，到点认领 CAS 保证每个 fireTime 恰一条执行记录；fireTime 同值断言验证 CAS
 * 值取理论触发时刻（A1）。
 */
@Tag("postgres")
class ScheduleExactlyOnceIT {

  private static EmbeddedPostgres postgres;
  private static ConfigurableApplicationContext replicaA;
  private static ConfigurableApplicationContext replicaB;
  private static String url;

  @BeforeAll
  static void startReplicas() throws IOException {
    postgres = EmbeddedPostgres.start();
    url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres?user=postgres";
    replicaA = boot("sched-a");
    replicaB = boot("sched-b");
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

  private static ConfigurableApplicationContext boot(String instanceId) throws IOException {
    Path root = seedWorkspace();
    return new SpringApplicationBuilder(OryxOsRuntime.class)
        .run(
            "--oryxos.root=" + root,
            "--oryxos.providers[0].name=mock",
            "--spring.datasource.url=" + url,
            "--oryxos.cluster.enabled=true",
            "--oryxos.cluster.instance-id=" + instanceId,
            "--memory.backend=sqlite",
            "--spring.lifecycle.timeout-per-shutdown-phase=100ms",
            "--spring.main.web-application-type=none");
  }

  @Test
  @DisplayName("every-2s schedule over ~10s: exactly one execution per fire time")
  void exactlyOneExecutionPerFireTime() throws Exception {
    assertNotNull(replicaA.getBean(AgentScheduler.class));
    assertNotNull(replicaB.getBean(AgentScheduler.class));

    Thread.sleep(11_000); // 每 2s 一次 ≈ 5 个到点，两副本调度器都在开火

    try (Connection conn = DriverManager.getConnection(url);
        Statement st = conn.createStatement()) {
      // 每个 fireTime（claimed_fire_time 单调推进）与执行记录一一对应：无双发
      try (ResultSet rs =
          st.executeQuery(
              "SELECT COUNT(*) AS total, COUNT(DISTINCT started_at) AS distinct_starts"
                  + " FROM task_executions")) {
        assertTrue(rs.next());
        long total = rs.getLong("total");
        assertTrue(total >= 3, "至少跑了 3 个周期，实际=" + total);
      }
      // 恰好一次的核心断言：按**到点时刻**分桶，同一到点至多 1 条执行。
      //
      // 这里必须用 fire_time，不能用 started_at —— 后者是执行开始时的墙钟（认领之后才取），
      // 两次相邻到点若各自被拖慢，started_at 会落进同一秒，按秒分桶就假阳性（issue #882）。
      // fire_time 是调度时算出的理论触发时刻，同一到点只可能有一个值。
      try (ResultSet rs =
          st.executeQuery(
              "SELECT fire_time, COUNT(*) AS n FROM task_executions"
                  + " WHERE fire_time IS NOT NULL GROUP BY fire_time HAVING COUNT(*) > 1")) {
        assertTrue(!rs.next(), "同一到点被执行了多次（双发）");
      }
      // 到点值必须落库：cron 触发的执行不该缺 fire_time（缺了上面的断言就是空转）
      try (ResultSet rs =
          st.executeQuery("SELECT COUNT(*) AS n FROM task_executions WHERE fire_time IS NULL")) {
        assertTrue(rs.next());
        assertEquals(0, rs.getLong("n"), "cron 触发的执行必须记下到点时刻");
      }
      // A1 验证：认领值 = 理论触发时刻（整 2 秒的日历点，微秒为零）
      try (ResultSet rs =
          st.executeQuery(
              "SELECT COUNT(*) FROM scheduled_tasks WHERE claimed_fire_time IS NOT NULL"
                  + " AND date_trunc('second', claimed_fire_time) = claimed_fire_time")) {
        assertTrue(rs.next());
        assertEquals(1, rs.getLong(1), "认领值必须是理论触发时刻（秒级日历点），绝非墙钟");
      }
    }
  }

  private static Path seedWorkspace() throws IOException {
    Path root = Files.createTempDirectory("oryxos-schedule-exactly-once");
    Files.createDirectories(root.resolve("memory"));
    Files.createDirectories(root.resolve("agents").resolve("cron-agent"));
    Files.writeString(
        root.resolve("agents/cron-agent/AGENT.md"),
        """
        ---
        name: cron-agent
        description: exactly-once fixture
        identity:
          agent_name: Cron Agent
          prompt: You are a test agent.
        provider:
          name: mock
          model: mock-model
        tools:
          - save_memory
        schedules:
          - key: every-2s
            name: Every two seconds
            cron: "*/2 * * * * *"
            message: tick
        settings:
          max_iterations: 3
          max_history_turns: 10
        ---
        Test fixture.
        """);
    return root;
  }
}
