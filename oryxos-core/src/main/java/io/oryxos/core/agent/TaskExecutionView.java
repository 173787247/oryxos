package io.oryxos.core.agent;

import java.time.Instant;

/** 定时任务一次执行的历史记录视图（成功失败都记）。供 GET /api/v1/schedules/{id}/executions。 */
public record TaskExecutionView(
    String scheduleId,
    String legacyTaskKey,
    boolean legacyMigrated,
    String sessionId,
    Instant startedAt,
    /** 到点时刻（理论触发值）；手动/补跑为 null。与 startedAt（执行开始墙钟）是两回事。 */
    Instant fireTime,
    boolean success,
    String errorMessage,
    long durationMs) {}
