package io.oryxos.core.knowledge;

import java.util.List;

/** 一个 Agent 的知识库绑定巡检快照：有效绑定 + 非法项。每次现算，无缓存（绑定唯一真相源是文件系统）。 */
public record KnowledgeBindingInspection(
    List<BoundKnowledgeDescriptor> bindings, List<KnowledgeBindingIssue> issues) {

  public KnowledgeBindingInspection {
    bindings = bindings == null ? List.of() : List.copyOf(bindings);
    issues = issues == null ? List.of() : List.copyOf(issues);
  }
}
