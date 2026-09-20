package io.oryxos.core.flow;

import java.util.List;
import java.util.Objects;

/** Convenience parse + validate entry for Markdown Flow documents (045 / #467). */
public final class FlowDocuments {

  private FlowDocuments() {}

  public record Result(FlowDefinition definition, List<FlowDiagnostic> diagnostics) {
    public Result {
      definition = Objects.requireNonNull(definition, "definition");
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public boolean ok() {
      return !FlowValidator.hasErrors(diagnostics);
    }
  }

  public static Result parseAndValidate(String markdown) {
    FlowDefinition definition = FlowMarkdown.parse(markdown);
    return new Result(definition, FlowValidator.validate(definition));
  }
}
