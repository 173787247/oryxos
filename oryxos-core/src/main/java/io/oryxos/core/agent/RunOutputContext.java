package io.oryxos.core.agent;

import java.util.Optional;
import java.util.UUID;

/** Per-turn output identity, independent of the per-tool audit context. */
public record RunOutputContext(String agent, String runId) {
  private static final ThreadLocal<RunOutputContext> CURRENT = new ThreadLocal<>();

  public RunOutputContext {
    if (agent == null
        || !agent.matches("[A-Za-z0-9_-]+")
        || runId == null
        || !runId.matches("[A-Za-z0-9_-]+")) {
      throw new IllegalArgumentException("Invalid output identity");
    }
  }

  public String relativeDirectory() {
    return "output/" + agent + "/" + runId;
  }

  public static Optional<RunOutputContext> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  public static Scope open(String agent) {
    RunOutputContext previous = CURRENT.get();
    CURRENT.set(new RunOutputContext(agent, UUID.randomUUID().toString()));
    return new Scope(previous);
  }

  public static final class Scope implements AutoCloseable {
    private final RunOutputContext previous;
    private boolean closed;

    private Scope(RunOutputContext previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      if (!closed) {
        if (previous == null) {
          CURRENT.remove();
        } else {
          CURRENT.set(previous);
        }
        closed = true;
      }
    }
  }
}
