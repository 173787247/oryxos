package io.oryxos.core.cost;

public record CostAttributionQuery(
    String runId, String taskId, String agentName, String teamId, String provider, String model) {}
