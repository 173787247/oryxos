package io.oryxos.web.controller.dto;

import io.oryxos.core.knowledge.model.KnowledgeBaseInfo;
import io.oryxos.core.knowledge.model.KnowledgeCapabilities;
import java.time.Instant;

/** 知识库列表/详情行：投影 + 后端能力集（管理台按能力渲染操作入口，FR-009）。 */
public record KnowledgeBaseView(
    String name,
    String description,
    String backend,
    int documentCount,
    int chunkCount,
    String indexStatus,
    Instant lastIndexedAt,
    CapabilitiesView capabilities) {

  public static KnowledgeBaseView from(KnowledgeBaseInfo info, KnowledgeCapabilities capabilities) {
    return new KnowledgeBaseView(
        info.name(),
        info.description(),
        info.backend(),
        info.documentCount(),
        info.chunkCount(),
        info.indexStatus(),
        info.lastIndexedAt(),
        capabilities == null ? null : CapabilitiesView.from(capabilities));
  }

  /** 能力声明视图：未注册后端返回 null（前端按只读渲染）。 */
  public record CapabilitiesView(
      boolean createDelete, boolean importDocs, boolean rebuild, boolean status, boolean rerank) {

    public static CapabilitiesView from(KnowledgeCapabilities capabilities) {
      return new CapabilitiesView(
          capabilities.createDelete(),
          capabilities.importDocs(),
          capabilities.rebuild(),
          capabilities.status(),
          capabilities.rerank());
    }
  }
}
