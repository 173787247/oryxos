package io.oryxos.web.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * 知识库使用看板（FR-023）：只消费 tool_invocations 审计数据聚合，不另建统计路径—— 每项指标都能用同一时间窗的审计 SQL 核对（SC-009）。
 *
 * @param retrievalCount 归属本库的检索次数（命中含本库，或调用显式限定本库）
 * @param zeroResultCount 归属本库的零结果次数（仅显式限定本库的调用可归属）
 * @param zeroResultRate 零结果率（分母为归属检索数；无数据为 null）
 * @param degradedCount 发生降级的检索次数
 * @param degradedRate 降级率（无数据为 null）
 * @param citationRate 出处引用率（命中出处出现在会话最终回答文本中的比例，近似度量；不可算为 null）
 * @param hitDocuments 命中文档分布（按命中次数降序）
 * @param zeroResultQueries 零结果查询原文（判断该补什么文档）
 * @param unattributedZeroResults 未限定单库、无法归属的零结果次数（跨库聚合调用），附原文
 */
public record KnowledgeMetricsView(
    Instant from,
    Instant to,
    long retrievalCount,
    long zeroResultCount,
    Double zeroResultRate,
    long degradedCount,
    Double degradedRate,
    Double citationRate,
    List<DocumentHits> hitDocuments,
    List<String> zeroResultQueries,
    long unattributedZeroResults,
    List<String> unattributedZeroResultQueries) {

  public KnowledgeMetricsView {
    hitDocuments = hitDocuments == null ? List.of() : List.copyOf(hitDocuments);
    zeroResultQueries = zeroResultQueries == null ? List.of() : List.copyOf(zeroResultQueries);
    unattributedZeroResultQueries =
        unattributedZeroResultQueries == null
            ? List.of()
            : List.copyOf(unattributedZeroResultQueries);
  }

  public record DocumentHits(String relPath, long hits) {}
}
