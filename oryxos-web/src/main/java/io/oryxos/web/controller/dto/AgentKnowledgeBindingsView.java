package io.oryxos.web.controller.dto;

import io.oryxos.core.knowledge.KnowledgeBindingInspection;
import java.util.List;

/** Agent 知识库绑定视图（含链接合法性状态，SC-007 三界面一致的对账面）。 */
public record AgentKnowledgeBindingsView(List<BindingView> bindings, List<IssueView> issues) {

  public AgentKnowledgeBindingsView {
    bindings = bindings == null ? List.of() : List.copyOf(bindings);
    issues = issues == null ? List.of() : List.copyOf(issues);
  }

  public static AgentKnowledgeBindingsView from(KnowledgeBindingInspection inspection) {
    return new AgentKnowledgeBindingsView(
        inspection.bindings().stream()
            .map(binding -> new BindingView(binding.name(), binding.description()))
            .toList(),
        inspection.issues().stream()
            .map(issue -> new IssueView(issue.entryName(), issue.type().name(), issue.message()))
            .toList());
  }

  public record BindingView(String name, String description) {}

  public record IssueView(String entryName, String type, String message) {}
}
