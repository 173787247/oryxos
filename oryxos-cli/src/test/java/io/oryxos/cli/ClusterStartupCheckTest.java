package io.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.oryxos.core.cluster.ClusterProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** 误配 fail-fast 三组合 + 单机档零校验（026 T008 / SC-009）+ 文件面诊断（只有 WARN，不拒启）。 */
class ClusterStartupCheckTest {

  private static final String POSTGRES = "jdbc:postgresql://db/oryxos";
  private static final String SHARED = "shared-posix";
  private static final String IDENTITY = "production-workspace-01";

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  @BeforeEach
  void setUp() {
    logger = (Logger) LoggerFactory.getLogger(ClusterStartupCheck.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(appender);
  }

  private static ClusterProperties cluster(boolean enabled) {
    ClusterProperties properties = new ClusterProperties();
    properties.setEnabled(enabled);
    return properties;
  }

  private List<ILoggingEvent> eventsAt(Level level) {
    return appender.list.stream().filter(event -> event.getLevel() == level).toList();
  }

  @Test
  void disabled_skipsAllChecks() {
    // 单机档：即使组合全是单机组件（含默认的 local 工作区）也零校验零变化，连诊断 WARN 都不该有
    assertThatCode(
            () ->
                new ClusterStartupCheck(
                        cluster(false), "jdbc:sqlite:oryxos.db", "markdown", "memory", "local", "")
                    .afterSingletonsInstantiated())
        .doesNotThrowAnyException();
    assertThat(eventsAt(Level.WARN)).isEmpty();
  }

  @Test
  void enabled_rejectsSqliteDatasource() {
    assertThatThrownBy(
            () ->
                new ClusterStartupCheck(
                        cluster(true),
                        "jdbc:sqlite:oryxos.db",
                        "sqlite",
                        "sqlite",
                        SHARED,
                        IDENTITY)
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PostgreSQL");
  }

  @Test
  void enabled_rejectsMarkdownMemory() {
    assertThatThrownBy(
            () ->
                new ClusterStartupCheck(
                        cluster(true), POSTGRES, "markdown", "sqlite", SHARED, IDENTITY)
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("memory.backend=sqlite");
  }

  @Test
  void enabled_rejectsInMemoryKnowledge() {
    assertThatThrownBy(
            () ->
                new ClusterStartupCheck(
                        cluster(true), POSTGRES, "sqlite", "memory", SHARED, IDENTITY)
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("knowledge.store=sqlite");
  }

  @Test
  void enabled_passesWithSharedComponents() {
    assertThatCode(
            () ->
                new ClusterStartupCheck(
                        cluster(true), POSTGRES, "sqlite", "sqlite", SHARED, IDENTITY)
                    .afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  private static ClusterProperties sharedCluster(Duration leaseTtl, Duration heartbeat) {
    ClusterProperties properties = cluster(true);
    properties.setLeaseTtl(leaseTtl);
    properties.setHeartbeatInterval(heartbeat);
    return properties;
  }

  private static void check(ClusterProperties properties) {
    new ClusterStartupCheck(properties, POSTGRES, "sqlite", "sqlite", SHARED, IDENTITY)
        .afterSingletonsInstantiated();
  }

  @Test
  void enabled_rejectsHeartbeatNotShorterThanLease() {
    // 续租间隔 45s、租约 30s：两次续租之间租约必然过期，两副本持续互踢。
    assertThatThrownBy(() -> check(sharedCluster(Duration.ofSeconds(30), Duration.ofSeconds(45))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lease-ttl");
  }

  @Test
  void enabled_rejectsHeartbeatEqualToLease() {
    assertThatThrownBy(() -> check(sharedCluster(Duration.ofSeconds(30), Duration.ofSeconds(30))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lease-ttl");
  }

  @Test
  void enabled_acceptsHeartbeatShorterThanLease() {
    assertThatCode(() -> check(sharedCluster(Duration.ofSeconds(30), Duration.ofSeconds(10))))
        .doesNotThrowAnyException();
  }

  @Test
  void enabled_acceptsDefaultHeartbeat() {
    // 不显式配 heartbeat 时缺省就是 leaseTtl/3，永远满足不变式。
    ClusterProperties properties = cluster(true);
    properties.setLeaseTtl(Duration.ofSeconds(30));
    assertThatCode(() -> check(properties)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("cluster + local 工作区：只告警一次、不拒启（同机多副本共享同一目录是合法场景）")
  void enabled_localWorkspace_warnsOnceButStillStarts() {
    ClusterStartupCheck check =
        new ClusterStartupCheck(cluster(true), POSTGRES, "sqlite", "sqlite", "local", "");

    assertThatCode(check::afterSingletonsInstantiated).doesNotThrowAnyException();

    List<ILoggingEvent> warnings = eventsAt(Level.WARN);
    assertThat(warnings).hasSize(1); // 每次进程启动一行，不随请求增长
    String warning = warnings.get(0).getFormattedMessage();
    assertThat(warning)
        .contains("oryxos.workspace.storage.provider=local") // 当前 provider 进日志
        .contains("shared-posix") // 跨主机该改成什么
        .contains("同一个目录") // 合法场景的判据，避免同机多副本的人误以为自己配错了
        .contains("重载成功但内容不同"); // 症状：版本号照常 bump，各副本从自己磁盘重载
    // provider / identity 进启动日志（identity 空时打 "-"）
    assertThat(eventsAt(Level.INFO))
        .anyMatch(event -> event.getFormattedMessage().contains("workspaceProvider=local"));
  }

  @Test
  @DisplayName("cluster + shared-posix：没有本地工作区告警，provider/identity 仍在启动日志里")
  void enabled_sharedWorkspace_hasNoWorkspaceWarning() {
    ClusterStartupCheck check =
        new ClusterStartupCheck(cluster(true), POSTGRES, "sqlite", "sqlite", SHARED, IDENTITY);

    assertThatCode(check::afterSingletonsInstantiated).doesNotThrowAnyException();

    assertThat(eventsAt(Level.WARN)).isEmpty();
    assertThat(eventsAt(Level.INFO))
        .anyMatch(
            event ->
                event.getFormattedMessage().contains("workspaceProvider=shared-posix")
                    && event.getFormattedMessage().contains("workspaceIdentity=" + IDENTITY));
  }
}
