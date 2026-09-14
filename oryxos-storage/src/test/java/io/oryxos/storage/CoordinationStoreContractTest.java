package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.core.cluster.CoordinationStore;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CoordinationStore CAS 语义契约（026，两库同套用例）：唯一约束互斥、抢过期、fencing rowcount、 回执判重、到点认领恰一胜、心跳
 * upsert。真并发恰一胜由 boot IT 覆盖，此处钉序列语义。
 *
 * <p>NOT_SUPPORTED 压制测试事务：saveAndFlush 捕约束冲突后若身处外层事务会被标记 rollback-only——
 * 生产调用方（AgentService/AgentScheduler/去重）均无外层事务，测试同形态运行。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class CoordinationStoreContractTest {

  @Autowired TurnLeaseRepository turnLeases;
  @Autowired ChannelEventReceiptRepository receipts;
  @Autowired ChannelLeaseRepository channelLeases;
  @Autowired InstanceHeartbeatRepository instances;
  @Autowired ScheduledTaskRepository scheduledTasks;
  @Autowired WorkspaceVersionRepository workspaceVersions;
  @Autowired KnowledgeBuildClaimRepository buildClaims;
  @Autowired KnowledgeGenerationRepository generations;

  private CoordinationStore store;

  private static final Duration TTL = Duration.ofSeconds(30);

  @BeforeEach
  void setUp() {
    store =
        new JpaCoordinationStore(
            turnLeases,
            receipts,
            channelLeases,
            instances,
            scheduledTasks,
            workspaceVersions,
            buildClaims,
            generations);
    turnLeases.deleteAll();
    receipts.deleteAll();
    channelLeases.deleteAll();
    instances.deleteAll();
    scheduledTasks.deleteAll();
    buildClaims.deleteAll();
    generations.deleteAll();
    resetWorkspaceVersions();
  }

  private void resetWorkspaceVersions() {
    // V8 预插 4 域行；测试只归零 version 不删行（运行期语义就是只 UPDATE 不增删）
    workspaceVersions
        .findAll()
        .forEach(
            row -> {
              row.setVersion(0L);
              row.setUpdatedBy("test");
              workspaceVersions.save(row);
            });
  }

  @Test
  void turnLease_uniqueConstraintIsMutex() {
    assertThat(store.tryAcquireTurn("s-1", "a@1", TTL)).isTrue();
    assertThat(store.tryAcquireTurn("s-1", "b@1", TTL)).isFalse(); // 未过期，抢不走
    assertThat(store.tryAcquireTurn("s-2", "b@1", TTL)).isTrue(); // 会话间零竞争
  }

  @Test
  void turnLease_expiredCanBeTaken_andDanglingExecutionSurfaces() {
    assertThat(store.tryAcquireTurn("s-1", "a@1", Duration.ofMillis(-1000))).isTrue(); // 立刻过期
    store.attachExecution("s-1", "a@1", 42L);

    assertThat(store.tryAcquireTurn("s-1", "b@1", TTL)).isTrue(); // 抢过期成功
    assertThat(store.lastReclaimedExecutionId()).isEqualTo(42L); // 前任悬空 execution 供留痕
    assertThat(store.tryAcquireTurn("s-1", "c@1", TTL)).isFalse(); // b 持有中，c 抢不走
  }

  @Test
  void turnLease_renewFencing() {
    assertThat(store.tryAcquireTurn("s-1", "a@1", TTL)).isTrue();
    assertThat(store.renewTurn("s-1", "a@1", TTL)).isTrue(); // 持有者续租成功
    assertThat(store.renewTurn("s-1", "b@1", TTL)).isFalse(); // 非持有者 rowcount=0
  }

  @Test
  void turnLease_releaseOnlyDeletesOwn() {
    assertThat(store.tryAcquireTurn("s-1", "a@1", TTL)).isTrue();
    store.releaseTurn("s-1", "b@1"); // 别人的 release 无效
    assertThat(store.tryAcquireTurn("s-1", "b@1", TTL)).isFalse(); // a 仍持有
    store.releaseTurn("s-1", "a@1");
    assertThat(store.tryAcquireTurn("s-1", "b@1", TTL)).isTrue(); // 释放后可认领
  }

  @Test
  void receipt_duplicateIsRejected() {
    assertThat(store.markReceipt("feishu:msg-1")).isTrue();
    assertThat(store.markReceipt("feishu:msg-1")).isFalse(); // 重复
    assertThat(store.markReceipt("feishu:msg-2")).isTrue();
  }

  @Test
  void claimFireTime_exactlyOncePerFireTime() {
    seedSchedule("sched-1");
    Instant fire = Instant.parse("2026-09-05T09:00:00Z");
    assertThat(store.claimFireTime("sched-1", fire, "a@1")).isTrue();
    assertThat(store.claimFireTime("sched-1", fire, "b@1")).isFalse(); // 同到点第二副本失败
    Instant nextFire = fire.plusSeconds(60);
    assertThat(store.claimFireTime("sched-1", nextFire, "b@1")).isTrue(); // 下一到点正常
    assertThat(store.claimFireTime("sched-1", fire, "a@1")).isFalse(); // 旧到点不可回认领
  }

  @Test
  void channelLease_sameSemanticsAsTurn() {
    assertThat(store.tryAcquireChannel("wecom-main", "a@1", TTL)).isTrue();
    assertThat(store.tryAcquireChannel("wecom-main", "b@1", TTL)).isFalse();
    assertThat(store.renewChannel("wecom-main", "b@1", TTL)).isFalse();
    store.releaseChannel("wecom-main", "a@1");
    assertThat(store.tryAcquireChannel("wecom-main", "b@1", TTL)).isTrue();
  }

  @Test
  void heartbeat_upsertsAndListsInstances() {
    store.heartbeat("inst-a", 100L);
    store.heartbeat("inst-a", 100L); // 二次 = 刷新心跳非新行
    store.heartbeat("inst-b", 200L);
    assertThat(store.listInstances()).hasSize(2);
    assertThat(
            store.listInstances().stream()
                .filter(i -> "inst-a".equals(i.instanceId()))
                .findFirst()
                .orElseThrow()
                .epoch())
        .isEqualTo(100L);
  }

  @Test
  void activeTurnLeases_excludesExpired() {
    assertThat(store.tryAcquireTurn("s-live", "a@1", TTL)).isTrue();
    assertThat(store.tryAcquireTurn("s-dead", "a@1", Duration.ofMillis(-1000))).isTrue();
    assertThat(store.activeTurnLeases()).hasSize(1);
    assertThat(store.activeTurnLeases().get(0).sessionId()).isEqualTo("s-live");
  }

  @Test
  void purgeExpired_cleansReceiptsAndDeadInstances() {
    store.markReceipt("old-key");
    store.heartbeat("inst-dead", 1L);
    store.purgeExpired(
        Duration.ofSeconds(-2), Duration.ofSeconds(-2)); // 负 TTL：全部判超龄（SQLite 秒精度下 TTL=0 有同秒边界）
    assertThat(receipts.count()).isZero();
    assertThat(instances.count()).isZero();
  }

  @Test
  void workspaceVersion_bumpIncrementsWithoutLoss() {
    long before = store.workspaceVersions().get("agents");
    store.bumpWorkspaceVersion("agents", "a@1");
    store.bumpWorkspaceVersion("agents", "b@1");
    assertThat(store.workspaceVersions().get("agents")).isEqualTo(before + 2); // 连续递增不丢
    assertThat(store.workspaceVersions().get("skills")).isEqualTo(0L); // 其他域不受影响
  }

  @Test
  void workspaceVersion_alwaysFourDomains_andRejectsUnknown() {
    assertThat(store.workspaceVersions())
        .containsOnlyKeys("agents", "skills", "personas", "knowledge");
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> store.bumpWorkspaceVersion("unknown", "a@1"));
  }

  @Test
  void indexBuildClaim_mutexTakeExpiredAndFencing() {
    assertThat(store.tryAcquireIndexBuild("kb-1", 2L, "a@1", TTL)).isTrue();
    assertThat(store.tryAcquireIndexBuild("kb-1", 2L, "b@1", TTL)).isFalse(); // 未过期抢不走
    assertThat(store.tryAcquireIndexBuild("kb-2", 1L, "b@1", TTL)).isTrue(); // 库间零竞争

    assertThat(store.tryAcquireIndexBuild("kb-3", 5L, "a@1", Duration.ofMillis(-1000))).isTrue();
    assertThat(store.tryAcquireIndexBuild("kb-3", 6L, "b@1", TTL)).isTrue(); // 抢过期成功（接管重建）
    assertThat(store.renewIndexBuild("kb-3", "a@1", TTL)).isFalse(); // 前任 fencing 失败
    assertThat(store.renewIndexBuild("kb-3", "b@1", TTL)).isTrue(); // 现任续租成功
  }

  @Test
  void commitGeneration_conditionalOnHolding() {
    assertThat(store.tryAcquireIndexBuild("kb-1", 3L, "a@1", TTL)).isTrue();
    assertThat(store.commitGeneration("kb-1", 3L, "a@1")).isTrue(); // 持有者提交生效
    assertThat(store.committedGeneration("kb-1")).hasValue(3L);
    assertThat(store.tryAcquireIndexBuild("kb-1", 4L, "b@1", TTL)).isTrue(); // 提交已释放认领

    assertThat(store.commitGeneration("kb-1", 9L, "a@1")).isFalse(); // 非持有者提交被拒
    assertThat(store.committedGeneration("kb-1")).hasValue(3L); // 代次未被污染
    assertThat(store.committedGeneration("kb-none")).isEmpty(); // 首建前空态
  }

  @Test
  void commitGeneration_bumpsKnowledgeDomain() {
    long before = store.workspaceVersions().get("knowledge");
    assertThat(store.tryAcquireIndexBuild("kb-1", 1L, "a@1", TTL)).isTrue();
    assertThat(store.commitGeneration("kb-1", 1L, "a@1")).isTrue();
    assertThat(store.workspaceVersions().get("knowledge")).isEqualTo(before + 1); // 提交即广播失效
  }

  @Test
  void releaseIndexBuild_onlyDeletesOwn() {
    assertThat(store.tryAcquireIndexBuild("kb-1", 1L, "a@1", TTL)).isTrue();
    store.releaseIndexBuild("kb-1", "b@1"); // 别人的 release 无效
    assertThat(store.tryAcquireIndexBuild("kb-1", 1L, "b@1", TTL)).isFalse(); // a 仍持有
    store.releaseIndexBuild("kb-1", "a@1");
    assertThat(store.tryAcquireIndexBuild("kb-1", 1L, "b@1", TTL)).isTrue(); // 释放后可认领
  }

  private void seedSchedule(String scheduleId) {
    ScheduledTask task = new ScheduledTask();
    task.setScheduleId(scheduleId);
    task.setProfileName("p");
    task.setScheduleKey("k");
    task.setDisplayName("k");
    task.setCron("0 0 9 * * *");
    task.setEnabled(true);
    task.setRetired(false);
    task.setRunCount(0);
    task.setUpdatedAt(Instant.now());
    scheduledTasks.save(task);
  }
}
