package io.oryxos.core.knowledge;

import java.util.List;

/** 删除仍被 Agent 引用的知识库时抛出（FR-011）；Web 层映射 409 并携带引用 Agent 清单 （照 SkillReferencedException 先例）。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "SE_BAD_FIELD",
    justification =
        "This in-process HTTP mapping exception is never Java-serialized; references are immutable response data.")
public class KnowledgeReferencedException extends IllegalStateException {

  private final String kbName;
  private final List<KnowledgeReference> references;

  public KnowledgeReferencedException(String kbName, List<KnowledgeReference> references) {
    super("知识库仍被 " + references.size() + " 个 Agent 引用，拒绝删除: " + kbName);
    this.kbName = kbName;
    this.references = List.copyOf(references);
  }

  public String kbName() {
    return kbName;
  }

  public List<KnowledgeReference> references() {
    return references;
  }
}
