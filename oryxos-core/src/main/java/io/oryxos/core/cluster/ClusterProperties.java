package io.oryxos.core.cluster;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多副本协调配置（026，oryxos.cluster.*）：enabled 默认 false = 单机档全部现状（NOOP 协调、进程内 去重、零协调写）。true
 * 时全套租约协调生效——单副本开着也正确（自己抢自己的），并触发 ClusterStartupCheck 的误配 fail-fast（SQLite / markdown 记忆 / memory
 * 知识库组合拒启）。
 *
 * <p>owner = {@code instanceId@startEpochMillis}：epoch 代次让快速重启的新进程不误继承旧代持有 （spec Edge Case
 * 代次隔离）。时间判定一律以数据库时间为准，本地时钟只用于间隔调度。
 */
@ConfigurationProperties(prefix = "oryxos.cluster")
public class ClusterProperties {

  private static final long START_EPOCH_MILLIS =
      ManagementFactory.getRuntimeMXBean().getStartTime();

  /** false = 单机档零变化；true = 租约协调 + 共享去重 + 心跳全套生效。 */
  private boolean enabled = false;

  /** 副本标识；缺省自动生成 主机名-pid（容器内 = pod 名-1，天然唯一）。 */
  private String instanceId = "";

  /** 轮次/渠道租约时长：过期即可被健康副本回收。 */
  private Duration leaseTtl = Duration.ofSeconds(30);

  /** 续租与心跳间隔；缺省 leaseTtl/3。 */
  private Duration heartbeatInterval;

  /** 同会话跨副本排队的等待轮询间隔。 */
  private Duration pollInterval = Duration.ofMillis(500);

  /** 同会话等待上限：超限抛 TurnWaitTimeoutException（IM 提示稍候重发 / API 429）。 */
  private Duration waitTimeout = Duration.ofSeconds(120);

  /** 有效实例标识（显式配置优先，否则 主机名-pid）。 */
  public String effectiveInstanceId() {
    if (instanceId != null && !instanceId.isBlank()) {
      return instanceId;
    }
    return defaultInstanceId();
  }

  /** 租约持有者身份：instanceId@startEpochMillis（代次隔离）。 */
  public String owner() {
    return effectiveInstanceId() + "@" + START_EPOCH_MILLIS;
  }

  public Duration effectiveHeartbeatInterval() {
    return heartbeatInterval != null ? heartbeatInterval : leaseTtl.dividedBy(3);
  }

  private static String defaultInstanceId() {
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      host = "unknown-host";
    }
    return host + "-" + ManagementFactory.getRuntimeMXBean().getPid();
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
  }

  public Duration getLeaseTtl() {
    return leaseTtl;
  }

  public void setLeaseTtl(Duration leaseTtl) {
    this.leaseTtl = leaseTtl;
  }

  public Duration getHeartbeatInterval() {
    return heartbeatInterval;
  }

  public void setHeartbeatInterval(Duration heartbeatInterval) {
    this.heartbeatInterval = heartbeatInterval;
  }

  public Duration getPollInterval() {
    return pollInterval;
  }

  public void setPollInterval(Duration pollInterval) {
    this.pollInterval = pollInterval;
  }

  public Duration getWaitTimeout() {
    return waitTimeout;
  }

  public void setWaitTimeout(Duration waitTimeout) {
    this.waitTimeout = waitTimeout;
  }
}
