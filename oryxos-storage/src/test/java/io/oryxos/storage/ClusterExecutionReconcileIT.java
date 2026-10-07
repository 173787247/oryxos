package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.AgentExecution;
import io.oryxos.core.agent.AgentExecutionService;
import io.oryxos.core.agent.AgentExecutionStore;
import io.oryxos.core.agent.AgentStopReasons;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 集群档：一个副本启动时的执行对账不得把他副本在途的 Run 判成失败。
 *
 * <p>两个 {@link AgentExecutionService} 实例共用同一个 JPA 存储（= 两副本打同一个库，各自独立的进程内 runningThreads
 * 视图）。真实部署里它们是两个进程； 本缺陷的判定输入是「共享库里他人的非终态行 + 本进程自己的 runningThreads 视图」，与是否同一 JVM 无关。
 *
 * <p>NOT_SUPPORTED 压制测试事务：两个副本服务各自开事务读写同一库，行必须在提交后才互相可见。
 */
@PostgresJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ClusterExecutionReconcileIT {

  @Autowired private AgentExecutionRepository repository;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> PgTestSupport.databaseUrl(ClusterExecutionReconcileIT.class));
  }

  @Test
  @DisplayName("副本 B 启动对账不得把副本 A 在途的 Run 判成 FAILED")
  void secondReplicaReconcileKeepsLiveReplicaRunRunning() throws Exception {
    AgentExecutionStore store = new JpaAgentExecutionStore(repository);
    ExecutorService executorA = Executors.newVirtualThreadPerTaskExecutor();
    ExecutorService executorB = Executors.newVirtualThreadPerTaskExecutor();
    try {
      // 副本 A（集群档）：启动对账已完成，随后领起一次定时执行，worker 停在 work 里。
      AgentExecutionService replicaA =
          new AgentExecutionService(store, executorA, Clock.systemUTC(), null, true);
      replicaA.reconcileOnStartup();
      CountDownLatch inFlight = new CountDownLatch(1);
      CountDownLatch finish = new CountDownLatch(1);
      long runId =
          replicaA.triggerAsync(
              "default",
              "schedule",
              "schedule-session",
              () -> {
                inFlight.countDown();
                await(finish);
              });
      assertTrue(inFlight.await(5, TimeUnit.SECONDS), "副本 A 的执行应已进入在途状态");
      assertEquals("RUNNING", store.findById(runId).orElseThrow().status());

      // 副本 B（集群档）：滚动升级期间启动——initMethod 对着同一个库跑启动对账。
      AgentExecutionService replicaB =
          new AgentExecutionService(store, executorB, Clock.systemUTC(), null, true);
      replicaB.reconcileOnStartup();

      AgentExecution afterReconcile = store.findById(runId).orElseThrow();
      assertEquals("RUNNING", afterReconcile.status(), "副本 A 仍在执行中，副本 B 的启动对账不得替它收口");
      assertNull(afterReconcile.endedAt());
      assertNull(afterReconcile.stopReason());

      // 副本 A 正常收口：它自己那份执行必须记为成功，sessionId 不能在收口前被抹掉。
      finish.countDown();
      replicaA.completeScheduledRun(runId, "schedule-session", true, null);
      AgentExecution finished = store.findById(runId).orElseThrow();
      assertEquals("SUCCESS", finished.status());
      assertEquals("schedule-session", finished.sessionId());
      assertThat(finished.success()).isTrue();
    } finally {
      executorA.shutdownNow();
      executorB.shutdownNow();
    }
  }

  @Test
  @DisplayName("集群档不做启动全量收敛：他副本崩溃遗留的非终态行留给租约路径收口")
  void clusterStartupLeavesOtherReplicaLeftoversToLeaseReclaim() {
    AgentExecutionStore store = new JpaAgentExecutionStore(repository);
    Instant now = Instant.now();
    // 崩溃副本的遗留行：无人收口，且早于本进程启动。
    long orphanId = store.start("default", "schedule", now.minusSeconds(120), "orphan");
    store.markRunning(orphanId, now.minusSeconds(120));

    AgentExecutionService replicaB =
        new AgentExecutionService(
            store, Executors.newVirtualThreadPerTaskExecutor(), Clock.systemUTC(), null, true);
    replicaB.reconcileOnStartup();

    // 026 契约：任何路径不得全量清除他人持有（含启动期）——集群档收口走租约过期，
    // 本进程没有证据判定他人行已中断，因此不得在此处置它。
    AgentExecution orphan = store.findById(orphanId).orElseThrow();
    assertEquals("RUNNING", orphan.status());
    assertNull(orphan.endedAt());
  }

  @Test
  @DisplayName("单机档语义不变：本副本上一代遗留的非终态 Run 仍被收敛为 FAILED")
  void singleReplicaRestartStillFailsItsOwnLeftoverRun() {
    AgentExecutionStore store = new JpaAgentExecutionStore(repository);
    Instant now = Instant.now();
    long leftoverId = store.start("default", "manual", now.minusSeconds(120), "leftover");
    store.markRunning(leftoverId, now.minusSeconds(120));

    // 单机档重启：遗留行都属于本副本上一代，必须收口（本进程 runningThreads 为空）。
    AgentExecutionService restarted =
        new AgentExecutionService(
            store,
            Executors.newVirtualThreadPerTaskExecutor(),
            Clock.fixed(now, Clock.systemUTC().getZone()));
    restarted.reconcileOnStartup();

    AgentExecution leftover = store.findById(leftoverId).orElseThrow();
    assertEquals("FAILED", leftover.status());
    assertEquals(AgentStopReasons.PROCESS_RESTARTED, leftover.stopReason());
    assertEquals(AgentStopReasons.MESSAGE_PROCESS_RESTARTED, leftover.errorMessage());
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
