package io.oryxos.core.flow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local Flow run store (tests / no JPA). Survives facade reload when the same instance is
 * kept.
 */
public final class InMemoryFlowRunStore implements FlowRunStore {

  private final Map<String, FlowRun> runs = new ConcurrentHashMap<>();
  private final Map<String, FlowStep> steps = new ConcurrentHashMap<>();
  private final Map<String, String> idempotencyToStep = new ConcurrentHashMap<>();

  @Override
  public FlowRun saveRun(FlowRun run) {
    runs.put(run.id(), run);
    return run;
  }

  @Override
  public Optional<FlowRun> findRun(String runId) {
    return Optional.ofNullable(runs.get(runId));
  }

  @Override
  public List<FlowRun> listRunsByState(FlowRunState state) {
    List<FlowRun> out = new ArrayList<>();
    for (FlowRun run : runs.values()) {
      if (run.state() == state) {
        out.add(run);
      }
    }
    out.sort(Comparator.comparing(FlowRun::createdAt));
    return List.copyOf(out);
  }

  @Override
  public FlowStep saveStep(FlowStep step) {
    steps.put(step.id(), step);
    idempotencyToStep.put(step.idempotencyKey(), step.id());
    return step;
  }

  @Override
  public Optional<FlowStep> findStep(String stepId) {
    return Optional.ofNullable(steps.get(stepId));
  }

  @Override
  public Optional<FlowStep> findStepByIdempotencyKey(String idempotencyKey) {
    String id = idempotencyToStep.get(idempotencyKey);
    return id == null ? Optional.empty() : findStep(id);
  }

  @Override
  public List<FlowStep> listSteps(String runId) {
    List<FlowStep> out = new ArrayList<>();
    for (FlowStep step : steps.values()) {
      if (step.runId().equals(runId)) {
        out.add(step);
      }
    }
    out.sort(Comparator.comparing(FlowStep::createdAt).thenComparing(FlowStep::nodeId));
    return List.copyOf(out);
  }

  @Override
  public Optional<FlowRun> tryTransitionRun(String runId, FlowRunState expected, FlowRun next) {
    synchronized (this) {
      FlowRun cur = runs.get(runId);
      if (cur == null || cur.state() != expected) {
        return Optional.empty();
      }
      runs.put(runId, next);
      return Optional.of(next);
    }
  }
}
