package io.oryxos.core.eval;

import java.util.List;
import java.util.Objects;

/**
 * One scored fixture case for Agent / Skill / Prompt / Flow (#472).
 *
 * <p>Observations are recorded outcomes (live run or golden fixture)—the harness scores them; it
 * does not invoke LLM/runtime itself in this thin cut.
 */
public record EvalCase(
    String id,
    EvalTargetKind kind,
    String name,
    boolean success,
    List<String> expectedTools,
    List<String> actualTools,
    List<String> expectedCitations,
    List<String> actualCitations,
    long latencyMs,
    long costMicros) {

  public EvalCase {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(name, "name");
    expectedTools = expectedTools == null ? List.of() : List.copyOf(expectedTools);
    actualTools = actualTools == null ? List.of() : List.copyOf(actualTools);
    expectedCitations = expectedCitations == null ? List.of() : List.copyOf(expectedCitations);
    actualCitations = actualCitations == null ? List.of() : List.copyOf(actualCitations);
    if (latencyMs < 0) {
      throw new IllegalArgumentException("latencyMs must be >= 0");
    }
    if (costMicros < 0) {
      throw new IllegalArgumentException("costMicros must be >= 0");
    }
  }
}
