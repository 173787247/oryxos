package io.oryxos.core.cost;

/** 工具调用归集项（053）：按 agent（可选再按工具名 {@code tool}）的工具调用次数。口径为账本 {@code sourceKind=TOOL} 的条目计数。 */
public record ToolUsageEntry(String agentName, String tool, long callCount) {}
