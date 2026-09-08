package io.oryxos.core.knowledge.model;

import java.util.List;

/**
 * 检索入参最小公约数（research D9：topK 之外的平台特有参数沉到各插件配置）。
 *
 * @param query 检索查询文本
 * @param topK 返回片段数上限（跨库聚合后的全局 top-K，Clarify-Q2）
 * @param kbNames 检索范围（由门面按发起 Agent 的绑定圈定，插件不自行扩大范围）
 */
public record KnowledgeQuery(String query, int topK, List<String> kbNames) {

  /** 未显式指定 topK 时的默认值（spec Assumptions）。 */
  public static final int DEFAULT_TOP_K = 5;

  public KnowledgeQuery {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("检索查询不能为空");
    }
    if (topK <= 0) {
      topK = DEFAULT_TOP_K;
    }
    kbNames = kbNames == null ? List.of() : List.copyOf(kbNames);
  }
}
