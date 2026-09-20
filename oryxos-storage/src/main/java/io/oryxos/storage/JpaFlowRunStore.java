package io.oryxos.storage;

import io.oryxos.core.flow.FlowNodeType;
import io.oryxos.core.flow.FlowRun;
import io.oryxos.core.flow.FlowRunState;
import io.oryxos.core.flow.FlowRunStore;
import io.oryxos.core.flow.FlowStep;
import io.oryxos.core.flow.FlowStepState;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** {@link FlowRunStore} 的 JPA 实现（046 / #468）。 */
public class JpaFlowRunStore implements FlowRunStore {

  private final FlowRunRepository runs;
  private final FlowStepRepository steps;

  public JpaFlowRunStore(FlowRunRepository runs, FlowStepRepository steps) {
    this.runs = runs;
    this.steps = steps;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FlowRun saveRun(FlowRun run) {
    runs.save(toEntity(run));
    return run;
  }

  @Override
  public Optional<FlowRun> findRun(String runId) {
    return runs.findById(runId).map(JpaFlowRunStore::toRun);
  }

  @Override
  public List<FlowRun> listRunsByState(FlowRunState state) {
    return runs.findByState(state.name()).stream().map(JpaFlowRunStore::toRun).toList();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FlowStep saveStep(FlowStep step) {
    steps.save(toEntity(step));
    return step;
  }

  @Override
  public Optional<FlowStep> findStep(String stepId) {
    return steps.findById(stepId).map(JpaFlowRunStore::toStep);
  }

  @Override
  public Optional<FlowStep> findStepByIdempotencyKey(String idempotencyKey) {
    return steps.findByIdempotencyKey(idempotencyKey).map(JpaFlowRunStore::toStep);
  }

  @Override
  public List<FlowStep> listSteps(String runId) {
    return steps.findByRunIdOrderByCreatedAtAscNodeIdAsc(runId).stream()
        .map(JpaFlowRunStore::toStep)
        .toList();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Optional<FlowRun> tryTransitionRun(String runId, FlowRunState expected, FlowRun next) {
    FlowRunEntity e = runs.findById(runId).orElse(null);
    if (e == null || !expected.name().equals(e.getState())) {
      return Optional.empty();
    }
    runs.save(toEntity(next));
    return Optional.of(next);
  }

  private static FlowRun toRun(FlowRunEntity e) {
    return new FlowRun(
        e.getId(),
        e.getFlowId(),
        e.getFlowVersion(),
        e.getDefinitionMarkdown(),
        FlowRunState.valueOf(e.getState()),
        e.getEntryNodeId(),
        e.getCurrentNodeId(),
        e.getInputsJson(),
        e.getContextJson(),
        e.getLastError(),
        e.getAttempt(),
        e.getCreatedAt(),
        e.getUpdatedAt());
  }

  private static FlowRunEntity toEntity(FlowRun run) {
    FlowRunEntity e = new FlowRunEntity();
    e.setId(run.id());
    e.setFlowId(run.flowId());
    e.setFlowVersion(run.flowVersion());
    e.setDefinitionMarkdown(run.definitionMarkdown());
    e.setState(run.state().name());
    e.setEntryNodeId(run.entryNodeId());
    e.setCurrentNodeId(run.currentNodeId());
    e.setInputsJson(run.inputsJson());
    e.setContextJson(run.contextJson());
    e.setLastError(run.lastError());
    e.setAttempt(run.attempt());
    e.setCreatedAt(run.createdAt());
    e.setUpdatedAt(run.updatedAt());
    return e;
  }

  private static FlowStep toStep(FlowStepEntity e) {
    return new FlowStep(
        e.getId(),
        e.getRunId(),
        e.getNodeId(),
        FlowNodeType.valueOf(e.getNodeType()),
        FlowStepState.valueOf(e.getState()),
        e.getAttempt(),
        e.getIdempotencyKey(),
        e.getInputsJson(),
        e.getOutputsJson(),
        e.getError(),
        e.getStartedAt(),
        e.getFinishedAt(),
        e.getExpiresAt(),
        e.getCreatedAt(),
        e.getUpdatedAt());
  }

  private static FlowStepEntity toEntity(FlowStep step) {
    FlowStepEntity e = new FlowStepEntity();
    e.setId(step.id());
    e.setRunId(step.runId());
    e.setNodeId(step.nodeId());
    e.setNodeType(step.nodeType().name());
    e.setState(step.state().name());
    e.setAttempt(step.attempt());
    e.setIdempotencyKey(step.idempotencyKey());
    e.setInputsJson(step.inputsJson());
    e.setOutputsJson(step.outputsJson());
    e.setError(step.error());
    e.setStartedAt(step.startedAt());
    e.setFinishedAt(step.finishedAt());
    e.setExpiresAt(step.expiresAt());
    e.setCreatedAt(step.createdAt());
    e.setUpdatedAt(step.updatedAt());
    return e;
  }
}
