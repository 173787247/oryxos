package io.oryxos.core.knowledge;

import io.oryxos.core.knowledge.model.KnowledgeHit;
import io.oryxos.core.knowledge.model.KnowledgeQuery;
import java.util.List;

/** 必选契约：所有后端插件必须实现（FR-006）。同步签名（宪法 VII，规避 AgentScope 全 Mono 之坑）。 */
public interface KnowledgeRetriever {

  /** 在 {@code query.kbNames()} 圈定的库范围内检索；每条结果必须带出处（或显式标注出处不可用）。 */
  List<KnowledgeHit> retrieve(KnowledgeQuery query);
}
