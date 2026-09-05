package io.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxos.core.cluster.ClusterProperties;
import org.junit.jupiter.api.Test;

/** 误配 fail-fast 三组合 + 单机档零校验（026 T008 / SC-009）。 */
class ClusterStartupCheckTest {

  private static ClusterProperties cluster(boolean enabled) {
    ClusterProperties properties = new ClusterProperties();
    properties.setEnabled(enabled);
    return properties;
  }

  @Test
  void disabled_skipsAllChecks() {
    // 单机档：即使组合全是单机组件也零校验零变化
    assertThatCode(
            () ->
                new ClusterStartupCheck(
                        cluster(false), "jdbc:sqlite:oryxos.db", "markdown", "memory")
                    .afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  void enabled_rejectsSqliteDatasource() {
    assertThatThrownBy(
            () ->
                new ClusterStartupCheck(cluster(true), "jdbc:sqlite:oryxos.db", "sqlite", "sqlite")
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PostgreSQL");
  }

  @Test
  void enabled_rejectsMarkdownMemory() {
    assertThatThrownBy(
            () ->
                new ClusterStartupCheck(
                        cluster(true), "jdbc:postgresql://db/oryxos", "markdown", "sqlite")
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("memory.backend=sqlite");
  }

  @Test
  void enabled_rejectsInMemoryKnowledge() {
    assertThatThrownBy(
            () ->
                new ClusterStartupCheck(
                        cluster(true), "jdbc:postgresql://db/oryxos", "sqlite", "memory")
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("knowledge.store=sqlite");
  }

  @Test
  void enabled_passesWithSharedComponents() {
    assertThatCode(
            () ->
                new ClusterStartupCheck(
                        cluster(true), "jdbc:postgresql://db/oryxos", "sqlite", "sqlite")
                    .afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }
}
