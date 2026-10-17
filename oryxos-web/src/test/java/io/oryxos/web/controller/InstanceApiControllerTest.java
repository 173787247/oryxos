package io.oryxos.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.cluster.ClusterProperties;
import io.oryxos.core.cluster.CoordinationStore;
import io.oryxos.core.cluster.CoordinationStore.InstanceInfo;
import io.oryxos.core.cluster.CoordinationStore.TurnLeaseInfo;
import io.oryxos.web.common.ApiResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 026 FR-008：GET /api/v1/instances 的存活判定——3×TTL 阈值与等号点边界。 */
class InstanceApiControllerTest {

  private static final Duration TTL = Duration.ofSeconds(30);
  private static final Duration DEAD_AFTER = TTL.multipliedBy(3);
  private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");

  @Test
  @DisplayName("边界：距最近心跳恰好 3×TTL 算活；早 1ns 算死、晚 1ns 算活")
  void aliveBoundaryIsPinnedAtThreeTtls() {
    assertThat(InstanceApiController.isAlive(NOW.minus(DEAD_AFTER), NOW, DEAD_AFTER))
        .as("恰好 3×TTL：>3×TTL 才判死，等号算活")
        .isTrue();
    assertThat(InstanceApiController.isAlive(NOW.minus(DEAD_AFTER).minusNanos(1), NOW, DEAD_AFTER))
        .as("比 3×TTL 早 1ns：判死")
        .isFalse();
    assertThat(InstanceApiController.isAlive(NOW.minus(DEAD_AFTER).plusNanos(1), NOW, DEAD_AFTER))
        .as("比 3×TTL 晚 1ns：算活")
        .isTrue();
  }

  @Test
  @DisplayName("端点：窗口是 3×TTL（2×TTL 活、4×TTL 死），并回显集群开关与现役轮次")
  @SuppressWarnings("unchecked")
  void listReportsClusterView() {
    Instant now = Instant.now();
    InstanceInfo young =
        new InstanceInfo(
            "young", 7L, now.minus(Duration.ofMinutes(5)), now.minus(TTL.multipliedBy(2)));
    InstanceInfo old =
        new InstanceInfo(
            "old", 8L, now.minus(Duration.ofMinutes(9)), now.minus(TTL.multipliedBy(4)));
    CoordinationStore store = mock(CoordinationStore.class);
    when(store.listInstances()).thenReturn(List.of(young, old));
    when(store.activeTurnLeases())
        .thenReturn(List.of(new TurnLeaseInfo("s-1", "young@7", now.plusSeconds(5), now)));
    ClusterProperties cluster = new ClusterProperties();
    cluster.setEnabled(true);
    cluster.setLeaseTtl(TTL);

    ApiResponse<Map<String, Object>> response = new InstanceApiController(store, cluster).list();

    assertThat(response.getCode()).isZero();
    Map<String, Object> data = response.getData();
    assertThat(data.get("clusterEnabled")).isEqualTo(true);
    List<Map<String, Object>> instances = (List<Map<String, Object>>) data.get("instances");
    assertThat(instances).hasSize(2);
    assertThat(instances).extracting(m -> m.get("instanceId")).containsExactly("young", "old");
    assertThat(instances).extracting(m -> m.get("alive")).containsExactly(true, false);
    assertThat(instances.get(0))
        .containsEntry("epoch", 7L)
        .containsEntry("startedAt", young.startedAt())
        .containsEntry("lastHeartbeatAt", young.lastHeartbeatAt());
    List<Map<String, Object>> activeTurns = (List<Map<String, Object>>) data.get("activeTurns");
    assertThat(activeTurns).hasSize(1);
    assertThat(activeTurns.get(0))
        .containsEntry("sessionId", "s-1")
        .containsEntry("owner", "young@7")
        .containsEntry("leaseUntil", now.plusSeconds(5))
        .containsEntry("acquiredAt", now);
  }
}
