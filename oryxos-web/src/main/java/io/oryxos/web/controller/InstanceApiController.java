package io.oryxos.web.controller;

import io.oryxos.core.cluster.ClusterProperties;
import io.oryxos.core.cluster.CoordinationStore;
import io.oryxos.web.common.ApiResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 副本可见性（026 FR-008）：GET /api/v1/instances——全部副本（id/代次/启动/最近心跳/存活判定， 距今 >3×TTL 判死）+
 * 现役轮次持有（谁在处理哪个会话）。单机档返回空清单（零协调写）。
 */
@RestController
@RequestMapping("/api/v1/instances")
public class InstanceApiController {

  private final CoordinationStore store;
  private final ClusterProperties cluster;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "注入的 store 与配置是 Spring 共享 Bean，本就不应防御性拷贝。")
  public InstanceApiController(CoordinationStore store, ClusterProperties cluster) {
    this.store = store;
    this.cluster = cluster;
  }

  @GetMapping
  public ApiResponse<Map<String, Object>> list() {
    Instant now = Instant.now();
    Duration deadAfter = cluster.getLeaseTtl().multipliedBy(3);
    List<Map<String, Object>> instances =
        store.listInstances().stream()
            .map(
                i ->
                    Map.<String, Object>of(
                        "instanceId", i.instanceId(),
                        "epoch", i.epoch(),
                        "startedAt", i.startedAt(),
                        "lastHeartbeatAt", i.lastHeartbeatAt(),
                        "alive", i.lastHeartbeatAt().isAfter(now.minus(deadAfter))))
            .toList();
    List<Map<String, Object>> activeTurns =
        store.activeTurnLeases().stream()
            .map(
                l ->
                    Map.<String, Object>of(
                        "sessionId", l.sessionId(),
                        "owner", l.owner(),
                        "acquiredAt", l.acquiredAt(),
                        "leaseUntil", l.leaseUntil()))
            .toList();
    return ApiResponse.ok(
        Map.of(
            "clusterEnabled", cluster.isEnabled(),
            "instances", instances,
            "activeTurns", activeTurns));
  }
}
