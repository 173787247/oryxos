package io.oryxos.core.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 轮询语义（027 US1）：变化才重载、读失败保快照、单域失败重试不阻断、基线失败首轮全量兜底。 */
class WorkspaceVersionPollerTest {

  /** 可控假存储：只实现版本号总线，其余方法测试不触达。 */
  static class FakeStore implements CoordinationStore {
    Map<String, Long> versions = new HashMap<>();
    boolean failRead;

    FakeStore() {
      CoordinationStore.WORKSPACE_DOMAINS.forEach(d -> versions.put(d, 0L));
    }

    @Override
    public Map<String, Long> workspaceVersions() {
      if (failRead) {
        throw new IllegalStateException("db down");
      }
      return Map.copyOf(versions);
    }

    @Override
    public void bumpWorkspaceVersion(String domain, String owner) {
      versions.merge(domain, 1L, Long::sum);
    }

    // ---- 以下为测试不触达的桩 ----
    @Override
    public boolean tryAcquireTurn(String s, String o, Duration t) {
      return false;
    }

    @Override
    public boolean renewTurn(String s, String o, Duration t) {
      return false;
    }

    @Override
    public void releaseTurn(String s, String o) {}

    @Override
    public void attachExecution(String s, String o, long id) {}

    @Override
    public Long lastReclaimedExecutionId() {
      return null;
    }

    @Override
    public boolean claimFireTime(String s, Instant f, String o) {
      return false;
    }

    @Override
    public boolean markReceipt(String r) {
      return false;
    }

    @Override
    public boolean tryAcquireChannel(String c, String o, Duration t) {
      return false;
    }

    @Override
    public boolean renewChannel(String c, String o, Duration t) {
      return false;
    }

    @Override
    public void releaseChannel(String c, String o) {}

    @Override
    public void heartbeat(String i, long e) {}

    @Override
    public List<InstanceInfo> listInstances() {
      return List.of();
    }

    @Override
    public List<TurnLeaseInfo> activeTurnLeases() {
      return List.of();
    }

    @Override
    public void purgeExpired(Duration r, Duration i) {}

    @Override
    public boolean tryAcquireIndexBuild(String k, long g, String o, Duration t) {
      return false;
    }

    @Override
    public boolean renewIndexBuild(String k, String o, Duration t) {
      return false;
    }

    @Override
    public void releaseIndexBuild(String k, String o) {}

    @Override
    public boolean commitGeneration(String k, long g, String o) {
      return false;
    }

    @Override
    public OptionalLong committedGeneration(String k) {
      return OptionalLong.empty();
    }
  }

  private static WorkspaceVersionPoller pollerOf(FakeStore store) {
    return new WorkspaceVersionPoller(
        store, new ClusterProperties(), new ThreadPoolTaskScheduler());
  }

  @Test
  void reloadsOnlyChangedDomains() {
    FakeStore store = new FakeStore();
    WorkspaceVersionPoller poller = pollerOf(store);
    AtomicInteger agents = new AtomicInteger();
    AtomicInteger skills = new AtomicInteger();
    poller.register("agents", agents::incrementAndGet);
    poller.register("skills", skills::incrementAndGet);

    poller.pollOnce(); // 基线（start 未调，首轮全量兜底）
    agents.set(0);
    skills.set(0);

    store.bumpWorkspaceVersion("agents", "a@1");
    poller.pollOnce();
    assertThat(agents.get()).isEqualTo(1); // 变化域重载
    assertThat(skills.get()).isZero(); // 未变域不动

    poller.pollOnce();
    assertThat(agents.get()).isEqualTo(1); // 无新变化不重复重载
  }

  @Test
  void readFailureKeepsSnapshotAndRecovers() {
    FakeStore store = new FakeStore();
    WorkspaceVersionPoller poller = pollerOf(store);
    AtomicInteger agents = new AtomicInteger();
    poller.register("agents", agents::incrementAndGet);
    poller.pollOnce();
    agents.set(0);

    store.bumpWorkspaceVersion("agents", "a@1");
    store.failRead = true;
    poller.pollOnce(); // 读失败：保快照不炸
    assertThat(agents.get()).isZero();

    store.failRead = false;
    poller.pollOnce(); // 恢复后自愈
    assertThat(agents.get()).isEqualTo(1);
  }

  @Test
  void reloaderFailureRetriesNextTickWithoutBlockingOthers() {
    FakeStore store = new FakeStore();
    WorkspaceVersionPoller poller = pollerOf(store);
    AtomicInteger attempts = new AtomicInteger();
    AtomicInteger skills = new AtomicInteger();
    poller.register(
        "agents",
        () -> {
          if (attempts.incrementAndGet() == 1) {
            throw new IllegalStateException("volume flake");
          }
        });
    poller.register("skills", skills::incrementAndGet);
    poller.pollOnce();
    attempts.set(0);
    skills.set(0);

    store.bumpWorkspaceVersion("agents", "a@1");
    store.bumpWorkspaceVersion("skills", "a@1");
    poller.pollOnce(); // agents 失败、skills 成功
    assertThat(attempts.get()).isEqualTo(1);
    assertThat(skills.get()).isEqualTo(1);

    poller.pollOnce(); // agents 下轮重试成功；skills 不重复
    assertThat(attempts.get()).isEqualTo(2);
    assertThat(skills.get()).isEqualTo(1);
  }

  @Test
  void rejectsUnknownDomainRegistration() {
    WorkspaceVersionPoller poller = pollerOf(new FakeStore());
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> poller.register("nope", () -> {}));
  }
}
