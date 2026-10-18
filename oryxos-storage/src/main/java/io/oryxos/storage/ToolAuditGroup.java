package io.oryxos.storage;

/** 按工具名分组的调用计数（016 看板）。{@code key} 为 null 表示未命名，由服务层映射展示文案。 */
public record ToolAuditGroup(String key, Long totalCount, Long successCount) {}
