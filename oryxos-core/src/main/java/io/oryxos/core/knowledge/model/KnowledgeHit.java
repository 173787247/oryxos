package io.oryxos.core.knowledge.model;

import java.util.Map;

/**
 * 一条检索命中。出处是硬契约（非空）；payload 是平台特有字段的逃生舱（highlight、rerank 分数等）， 契约字段保持稳定（research D9）。
 *
 * @param citation 出处，不得为 null
 * @param content 片段文本
 * @param score 融合后相关性分数（跨库可比：RRF 名次分）
 * @param degraded 本条结果产生于降级路径（embedding 不可用走关键词，FR-013）
 * @param payload 平台特有附加字段；无则空 Map
 */
public record KnowledgeHit(
    Citation citation,
    String content,
    double score,
    boolean degraded,
    Map<String, Object> payload) {

  public KnowledgeHit {
    if (citation == null) {
      throw new IllegalArgumentException("检索命中必须携带出处（Citation 一等公民硬契约）");
    }
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }
}
