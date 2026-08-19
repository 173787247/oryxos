package io.oryxos.core.knowledge.model;

import java.time.Instant;

/**
 * 知识库列表投影：管理台列表页 / CLI {@code oryxos knowledge list} / REST 共用（FR-009/021）。
 *
 * @param name 库名（目录名，唯一）
 * @param description 描述（KNOWLEDGE.md frontmatter）
 * @param backend 后端插件名（缺省 local）
 * @param documentCount 文档数
 * @param chunkCount 片段数
 * @param indexStatus 汇总索引状态（就绪 / 索引中 / 失败 / 空）
 * @param lastIndexedAt 最近索引时间，可空
 */
public record KnowledgeBaseInfo(
    String name,
    String description,
    String backend,
    int documentCount,
    int chunkCount,
    String indexStatus,
    Instant lastIndexedAt) {}
