package io.oryxos.web.controller.dto;

import java.util.List;

/** PUT /agents/{name}/knowledge 请求体：整体替换该 Agent 的知识库绑定集合。 */
public record ReplaceKnowledgeBindingsRequest(List<String> knowledge) {
  public ReplaceKnowledgeBindingsRequest {
    knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
  }
}
