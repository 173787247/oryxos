package io.oryxos.web.controller.dto;

import java.util.List;

/** 知识库详情：库信息 + 文档清单。 */
public record KnowledgeBaseDetailView(
    KnowledgeBaseView base, List<KnowledgeDocumentView> documents) {
  public KnowledgeBaseDetailView {
    documents = documents == null ? List.of() : List.copyOf(documents);
  }
}
