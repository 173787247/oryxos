package io.oryxos.core.cluster;

import io.oryxos.core.metrics.MetricsRecorder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 工作区版本号轮询器（027，集群档专用）：每 tick 一次单查询读全部域版本号（表恒 4 行）， 与本地 last-seen 比对，变化域触发注册的重载回调——「DB
 * 作通知总线、文件作内容载体」的消费端。 单机档不装配本类（watcher 原样，FR-003/FR-010）。
 *
 * <p>失败语义（spec Edge Case）：总线读失败保留 last-seen 快照并 WARN（下轮自愈，不清空注册表）； 单域重载失败不推进该域
 * last-seen（下轮重试），也不阻断其他域。
 */
public class WorkspaceVersionPoller {

  private static final Logger LOG = LoggerFactory.getLogger(WorkspaceVersionPoller.class);

  private final CoordinationStore store;
  private final ClusterProperties cluster;
  private final ThreadPoolTaskScheduler scheduler;
  private final Map<String, Runnable> reloaders = new ConcurrentHashMap<>();
  private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

  private MetricsRecorder metricsRecorder = MetricsRecorder.NOOP;
  private volatile boolean baselineReady;
  private volatile ScheduledFuture<?> future;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "store/cluster/scheduler 均为装配层注入的共享单例，存同一引用正是意图。")
  public WorkspaceVersionPoller(
      CoordinationStore store, ClusterProperties cluster, ThreadPoolTaskScheduler scheduler) {
    this.store = store;
    this.cluster = cluster;
    this.scheduler = scheduler;
  }

  public void setMetricsRecorder(MetricsRecorder metricsRecorder) {
    this.metricsRecorder = metricsRecorder == null ? MetricsRecorder.NOOP : metricsRecorder;
  }

  /** 注册某域的重载回调（agents/skills/personas/knowledge）；装配期调用，先于 start。 */
  public void register(String domain, Runnable reloader) {
    if (!CoordinationStore.WORKSPACE_DOMAINS.contains(domain)) {
      throw new IllegalArgumentException("未知的工作区域: " + domain);
    }
    reloaders.put(domain, reloader);
  }

  /** 启动：先取基线（注册表刚按盘面建好，基线即当下），再按 workspace-poll-interval 固定间隔轮询。 */
  public void start() {
    try {
      lastSeen.putAll(store.workspaceVersions());
      baselineReady = true;
    } catch (RuntimeException e) {
      // 基线读失败：首个成功 tick 会全量重载一次兜底（宁可多载一轮，不漏窗口期变更）
      LOG.warn("工作区版本基线读取失败，首个成功轮询将全量重载: {}", sanitize(e.getMessage()));
    }
    future =
        scheduler.scheduleWithFixedDelay(
            this::pollOnce, java.time.Instant.now(), cluster.getWorkspacePollInterval());
    LOG.info("工作区版本轮询启动: interval={}", cluster.getWorkspacePollInterval());
  }

  public void stop() {
    ScheduledFuture<?> current = future;
    if (current != null) {
      current.cancel(false);
    }
  }

  /** 单个 tick（测试直呼此方法钉语义，不依赖真调度线程）。 */
  public void pollOnce() {
    Map<String, Long> versions;
    try {
      versions = store.workspaceVersions();
    } catch (RuntimeException e) {
      LOG.warn("工作区版本轮询读失败，保留当前注册表快照: {}", sanitize(e.getMessage()));
      return;
    }
    boolean forceReload = !baselineReady;
    baselineReady = true;
    for (Map.Entry<String, Long> entry : versions.entrySet()) {
      String domain = entry.getKey();
      long version = entry.getValue();
      Long seen = lastSeen.get(domain);
      if (!forceReload && seen != null && seen >= version) {
        continue;
      }
      Runnable reloader = reloaders.get(domain);
      if (reloader == null) {
        lastSeen.put(domain, version);
        continue;
      }
      try {
        reloader.run();
        lastSeen.put(domain, version);
        metricsRecorder.recordWorkspaceReloaded(domain);
        LOG.info("工作区域重载完成: domain={} version={}", sanitize(domain), version);
      } catch (RuntimeException e) {
        // 不推进 last-seen：下轮重试；单域失败不阻断其他域
        LOG.warn("工作区域重载失败（下轮重试）: domain={} err={}", sanitize(domain), sanitize(e.getMessage()));
      }
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
