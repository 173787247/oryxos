package io.oryxos.web.controller.dto;

import io.oryxos.core.knowledge.KnowledgeReference;
import java.util.List;

/** 删除被引用知识库的 409 冲突载荷：点名引用它的 Agent（FR-011）。 */
public record KnowledgeReferenceConflictView(String kbName, List<ReferenceView> references) {

  public KnowledgeReferenceConflictView {
    references = references == null ? List.of() : List.copyOf(references);
  }

  public static KnowledgeReferenceConflictView from(
      String kbName, List<KnowledgeReference> references) {
    return new KnowledgeReferenceConflictView(
        kbName,
        references.stream()
            .map(
                reference ->
                    new ReferenceView(
                        reference.agentName(), reference.state().name(), reference.directoryName()))
            .toList());
  }

  public record ReferenceView(String agentName, String state, String directoryName) {}
}
