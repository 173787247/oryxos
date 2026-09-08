package io.oryxos.core.knowledge.model;

import java.time.Instant;

/**
 * 库详情页的文档清单行（FR-008 索引状态可随时查询）。
 *
 * @param relPath 库内相对路径
 * @param state 状态机当前状态
 * @param chunkCount 片段数（未就绪为 0）
 * @param failureReason FAILED 时的可读原因，其余为 null
 * @param indexedAt 最近成功索引时间，可空
 */
public record DocumentStatus(
    String relPath, DocumentState state, int chunkCount, String failureReason, Instant indexedAt) {}
