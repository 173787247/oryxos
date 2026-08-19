package io.oryxos.web.controller.dto;

import io.oryxos.core.knowledge.model.DocumentStatus;
import java.time.Instant;

/** 库详情的文档清单行（状态机可随时查询，FR-008）。 */
public record KnowledgeDocumentView(
    String relPath, String state, int chunkCount, String failureReason, Instant indexedAt) {

  public static KnowledgeDocumentView from(DocumentStatus status) {
    return new KnowledgeDocumentView(
        status.relPath(),
        status.state().name(),
        status.chunkCount(),
        status.failureReason(),
        status.indexedAt());
  }
}
