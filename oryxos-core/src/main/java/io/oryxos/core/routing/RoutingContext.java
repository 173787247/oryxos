package io.oryxos.core.routing;

/**
 * Optional per-turn routing signals (#477). Mirrors CostContext: open at entry, close in finally.
 */
public final class RoutingContext {

  private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

  private RoutingContext() {}

  public record State(
      TaskDifficulty difficulty,
      DataSensitivity sensitivity,
      Integer maxLatencyMs,
      Long estimatedPromptTokens) {}

  public static Scope open(
      TaskDifficulty difficulty,
      DataSensitivity sensitivity,
      Integer maxLatencyMs,
      Long estimatedPromptTokens) {
    State prev = CURRENT.get();
    CURRENT.set(new State(difficulty, sensitivity, maxLatencyMs, estimatedPromptTokens));
    return new Scope(prev);
  }

  public static State current() {
    return CURRENT.get();
  }

  public static final class Scope implements AutoCloseable {
    private final State previous;

    private Scope(State previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }
}
