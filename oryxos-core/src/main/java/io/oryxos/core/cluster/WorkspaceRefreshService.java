package io.oryxos.core.cluster;

import java.time.Instant;
import java.util.List;

/**
 * 手动「刷新工作区」（027 FR-011，运维逃生舱）：绕过管理台直接修改共享卷后主动触发全副本重载。 集群档递增全部域版本号（各副本下一轮轮询生效，复用同一总线不特设第二条通知路）；
 * 单机档直接本地全量重载（watcher 机制不受影响）。装配层以 lambda 注入两种模式的动作。
 */
public class WorkspaceRefreshService {

  /** 刷新结果（web 层直接投影为响应体）；domains 经 List.copyOf 定格为不可变。 */
  public record RefreshResult(String mode, List<String> domains, Instant triggeredAt) {
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
        value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
        justification = "紧凑构造器 List.copyOf 已定格不可变，返回该引用无暴露风险。")
    public RefreshResult {
      domains = List.copyOf(domains);
    }
  }

  private final boolean clusterEnabled;
  private final WorkspaceVersionNotifier notifier;
  private final Runnable localReload;

  public WorkspaceRefreshService(
      boolean clusterEnabled, WorkspaceVersionNotifier notifier, Runnable localReload) {
    this.clusterEnabled = clusterEnabled;
    this.notifier = notifier;
    this.localReload = localReload;
  }

  public RefreshResult refresh() {
    List<String> domains = List.of("agents", "skills", "personas", "knowledge");
    if (clusterEnabled) {
      domains.forEach(notifier::bump);
      return new RefreshResult("cluster", domains, Instant.now());
    }
    localReload.run();
    return new RefreshResult("standalone", domains, Instant.now());
  }
}
