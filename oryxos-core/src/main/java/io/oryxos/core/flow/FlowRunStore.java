package io.oryxos.core.flow;

import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for Flow runs and steps (046 / #468). Core owns the interface; JPA in
 * storage.
 */
public interface FlowRunStore {

  FlowRun saveRun(FlowRun run);

  Optional<FlowRun> findRun(String runId);

  List<FlowRun> listRunsByState(FlowRunState state);

  FlowStep saveStep(FlowStep step);

  Optional<FlowStep> findStep(String stepId);

  Optional<FlowStep> findStepByIdempotencyKey(String idempotencyKey);

  List<FlowStep> listSteps(String runId);

  /** Atomic run state transition; empty on conflict. */
  Optional<FlowRun> tryTransitionRun(String runId, FlowRunState expected, FlowRun next);
}
