package io.oryxos.core.knowledge;

import io.oryxos.core.knowledge.model.KnowledgeBaseInfo;
import io.oryxos.core.knowledge.model.KnowledgeHit;
import java.util.List;

/**
 * 知识门面——运行时唯一入口：圈定发起 Agent 的绑定库范围 → 逐库路由后端 → 跨库融合取全局 top-K（FR-020 /
 * Clarify-Q2）。实现随运行时接线落地（impl-B）；契约先行以便 ContextLoader 消费。
 */
public interface KnowledgeService {

  /**
   * 在 Agent 绑定库范围内检索。
   *
   * @param agentName 发起 Agent（检索范围 = 其绑定库，FR-004）
   * @param query 检索查询
   * @param topK 可空；空取默认值
   * @param kbNameOrNull 可选限定单库；不存在或未绑定时抛出可读 {@link IllegalArgumentException}
   */
  List<KnowledgeHit> retrieveForAgent(
      String agentName, String query, Integer topK, String kbNameOrNull);

  /** 全部知识库投影（管理台列表 / CLI / REST 共用）。 */
  List<KnowledgeBaseInfo> listBases();
}
