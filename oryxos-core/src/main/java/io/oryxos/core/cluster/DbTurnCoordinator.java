package io.oryxos.core.cluster;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

/**
 * 共享库轮次协调（026 集群档）：acquire 阻塞轮询认领（同会话跨副本排队与单机排队语义等价）， 成功后按 TTL/3 续租——续租失败（租约被回收/身份失效）触发 fencing
 * 双闸之一：中断持有线程 「尽快停」；另一闸是 AgentService 写回前的 {@link TurnLease#stillHeld()} 硬校验「绝不写」。
 *
 * <p>同步阻塞 + 虚拟线程（宪法 VII）：等待用 Thread.sleep；续租任务挂既有 TaskScheduler。
 */
public class DbTurnCoordinator implements TurnCoordinator {

  private static final Logger log = LoggerFactory.getLogger(DbTurnCoordinator.class);

  private final CoordinationStore store;
  private final ClusterProperties properties;
  private final TaskScheduler renewalScheduler;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "注入的 store/scheduler 是 Spring 共享 Bean，本就不应防御性拷贝。")
  public DbTurnCoordinator(
      CoordinationStore store, ClusterProperties properties, TaskScheduler renewalScheduler) {
    this.store = store;
    this.properties = properties;
    this.renewalScheduler = renewalScheduler;
  }

  @Override
  public TurnLease acquire(String sessionId) {
    String owner = properties.owner();
    Duration ttl = properties.getLeaseTtl();
    long deadline = System.nanoTime() + properties.getWaitTimeout().toNanos();
    while (!store.tryAcquireTurn(sessionId, owner, ttl)) {
      if (System.nanoTime() >= deadline) {
        throw new TurnWaitTimeoutException(sessionId);
      }
      try {
        Thread.sleep(properties.getPollInterval().toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("等待会话轮次时被中断: " + sanitize(sessionId), e);
      }
    }
    Long danglingExecution = store.lastReclaimedExecutionId();
    if (danglingExecution != null) {
      log.warn("回收过期轮次租约: session={} 前任悬空 execution={}", sanitize(sessionId), danglingExecution);
    }
    DbTurnLease lease = new DbTurnLease(sessionId, owner, Thread.currentThread());
    lease.renewalTask =
        renewalScheduler.scheduleAtFixedRate(
            () -> renew(lease), Instant.now().plus(renewalInterval()), renewalInterval());
    return lease;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "sessionId 经 sanitize 去 CRLF，owner 为内部构造的 instanceId@epoch 且同样 sanitize；工具不识别自定义净化。")
  private void renew(DbTurnLease lease) {
    if (!lease.valid.get()) {
      return;
    }
    boolean held;
    try {
      held = store.renewTurn(lease.sessionId, lease.owner, properties.getLeaseTtl());
    } catch (RuntimeException e) {
      // 续租的存储抖动不立即判失：写回前 stillHeld 硬闸兜底
      log.warn("轮次续租异常（下一周期重试）: session={}", sanitize(lease.sessionId), e);
      return;
    }
    if (!held) {
      lease.valid.set(false);
      cancelRenewal(lease);
      log.warn(
          "轮次续租失败——租约已被回收，中断执行（fencing）: session={} owner={}",
          sanitize(lease.sessionId),
          sanitize(lease.owner));
      lease.holderThread.interrupt();
    }
  }

  @Override
  public void release(String sessionId, TurnLease lease) {
    if (lease instanceof DbTurnLease dbLease) {
      dbLease.valid.set(false);
      cancelRenewal(dbLease);
      store.releaseTurn(sessionId, dbLease.owner);
    }
  }

  private void cancelRenewal(DbTurnLease lease) {
    ScheduledFuture<?> task = lease.renewalTask;
    if (task != null) {
      task.cancel(false);
    }
  }

  private Duration renewalInterval() {
    return properties.effectiveHeartbeatInterval();
  }

  private static String sanitize(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }

  /** 抢过期时取回的前任悬空 execution（供装配层补失败留痕）。 */
  public Long lastReclaimedExecutionId() {
    return store.lastReclaimedExecutionId();
  }

  private final class DbTurnLease implements TurnLease {

    private final String sessionId;
    private final String owner;
    private final Thread holderThread;
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private volatile ScheduledFuture<?> renewalTask;

    private DbTurnLease(String sessionId, String owner, Thread holderThread) {
      this.sessionId = sessionId;
      this.owner = owner;
      this.holderThread = holderThread;
    }

    @Override
    public boolean stillHeld() {
      // 硬闸：以一次 owner 条件续租作为持有证明（rowcount 语义，顺带延长租约）
      return valid.get() && store.renewTurn(sessionId, owner, properties.getLeaseTtl());
    }

    @Override
    public void attachExecution(long agentExecutionId) {
      store.attachExecution(sessionId, owner, agentExecutionId);
    }
  }
}
