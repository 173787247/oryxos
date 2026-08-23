package io.oryxos.web.controller.dto;

/** 分布分组项：按模型 / provider / 工具名 / Agent 分组。totalCostMicros 仅 LLM 分组有值（工具不折算成本）。 */
public record AuditGroupView(String key, long count, long successCount, Long totalCostMicros) {}
