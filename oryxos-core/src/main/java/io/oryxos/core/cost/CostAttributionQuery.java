package io.oryxos.core.cost;

import java.time.Instant;

/**
 * 成本归因查询条件（#476 / 053）。
 *
 * <p>六个维度过滤（可空=不限）+ 可空时间范围 {@code from}（含）/{@code to}（不含），按账本条目 {@code createdAt}
 * 过滤。保留六参构造器，既有调用点不变（向后兼容）。
 */
public record CostAttributionQuery(
    String runId,
    String taskId,
    String agentName,
    String teamId,
    String provider,
    String model,
    Instant from,
    Instant to) {

  /** 向后兼容：无时间范围（from/to 置空）。 */
  public CostAttributionQuery(
      String runId, String taskId, String agentName, String teamId, String provider, String model) {
    this(runId, taskId, agentName, teamId, provider, model, null, null);
  }
}
