package io.oryxos.core.cost;

/**
 * Optional attribution overrides for the current turn (#476). Mirrors TraceContext discipline: open
 * at entry, close in finally.
 */
public final class CostContext {

  private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

  private CostContext() {}

  public record State(String taskId, String teamId, String runId) {}

  public static Scope open(String taskId, String teamId, String runId) {
    State prev = CURRENT.get();
    CURRENT.set(new State(blankToNull(taskId), blankToNull(teamId), blankToNull(runId)));
    return new Scope(prev);
  }

  public static State current() {
    return CURRENT.get();
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v;
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
