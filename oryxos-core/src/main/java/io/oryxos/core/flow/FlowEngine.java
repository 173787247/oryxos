package io.oryxos.core.flow;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Durable Markdown Flow execution engine (046 / #468): validate → persist run/steps → execute →
 * resume after restart; skip already-SUCCEEDED idempotent nodes.
 *
 * <p>HUMAN/APPROVAL enter {@link FlowRunState#WAITING}; callers use {@link #completeWaiting} (full
 * HITL UX is #469).
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "CRLF_INJECTION_LOGS",
    justification = "Log args are run/node ids sanitized via strip/UUID.")
public final class FlowEngine {

  private static final Logger LOG = LoggerFactory.getLogger(FlowEngine.class);
  private static final int MAX_STEPS_PER_ADVANCE = 256;

  private final FlowRunStore store;
  private final FlowNodeHandler handler;
  private final Clock clock;
  private final boolean enabled;
  private final int defaultMaxRetries;

  public FlowEngine(FlowRunStore store, FlowNodeHandler handler, Clock clock, boolean enabled) {
    this(store, handler, clock, enabled, 0);
  }

  public FlowEngine(
      FlowRunStore store,
      FlowNodeHandler handler,
      Clock clock,
      boolean enabled,
      int defaultMaxRetries) {
    this.store = Objects.requireNonNull(store, "store");
    this.handler = Objects.requireNonNull(handler, "handler");
    this.clock = clock == null ? Clock.systemUTC() : clock;
    this.enabled = enabled;
    this.defaultMaxRetries = Math.max(0, defaultMaxRetries);
  }

  public boolean enabled() {
    return enabled;
  }

  /** Start a validated Markdown Flow; returns the terminal or WAITING run snapshot. */
  public FlowRun start(String markdown, Map<String, Object> inputs) {
    requireEnabled();
    FlowDocuments.Result parsed = FlowDocuments.parseAndValidate(markdown);
    if (!parsed.ok()) {
      throw new IllegalArgumentException(
          "Flow validation failed: "
              + parsed.diagnostics().stream()
                  .filter(FlowDiagnostic::isError)
                  .map(FlowDiagnostic::code)
                  .toList());
    }
    return start(parsed.definition(), markdown, inputs);
  }

  public FlowRun start(
      FlowDefinition definition, String definitionMarkdown, Map<String, Object> inputs) {
    requireEnabled();
    Objects.requireNonNull(definition, "definition");
    List<FlowDiagnostic> diagnostics = FlowValidator.validate(definition);
    if (FlowValidator.hasErrors(diagnostics)) {
      throw new IllegalArgumentException("Flow validation failed: " + diagnostics);
    }
    Instant now = clock.instant();
    String runId = "fr-" + UUID.randomUUID().toString().replace("-", "");
    FlowRun run =
        new FlowRun(
            runId,
            definition.id(),
            definition.version(),
            definitionMarkdown == null ? "" : definitionMarkdown,
            FlowRunState.RUNNING,
            definition.entry(),
            definition.entry(),
            FlowJson.write(inputs == null ? Map.of() : inputs),
            "{}",
            null,
            0,
            now,
            now);
    store.saveRun(run);
    LOG.info("flow run started id={} flow={}", runId, definition.id());
    return advance(runId);
  }

  /**
   * Resume a RUNNING or WAITING run after process restart (WAITING needs {@link #completeWaiting}).
   */
  public FlowRun resume(String runId) {
    requireEnabled();
    FlowRun run =
        store
            .findRun(runId)
            .orElseThrow(() -> new IllegalArgumentException("unknown run: " + runId));
    if (run.state().terminal()) {
      return run;
    }
    if (run.state() == FlowRunState.WAITING) {
      return run;
    }
    if (run.state() == FlowRunState.QUEUED) {
      Instant now = clock.instant();
      run = store.saveRun(run.withState(FlowRunState.RUNNING, now));
    }
    return advance(run.id());
  }

  /**
   * Complete a WAITING human/approval step with outputs, then continue. Minimal hook for #468;
   * compensation/timeline UI remain #469.
   */
  public FlowRun completeWaiting(String runId, Map<String, Object> outputs) {
    requireEnabled();
    Instant now = clock.instant();
    FlowRun run =
        store
            .findRun(runId)
            .orElseThrow(() -> new IllegalArgumentException("unknown run: " + runId));
    if (run.state() != FlowRunState.WAITING) {
      throw new IllegalStateException("run is not WAITING: " + run.state());
    }
    String nodeId = run.currentNodeId();
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalStateException("WAITING run missing currentNodeId");
    }
    String key = FlowStep.idempotencyKeyFor(runId, nodeId);
    FlowStep step =
        store
            .findStepByIdempotencyKey(key)
            .orElseThrow(() -> new IllegalStateException("missing WAITING step for " + nodeId));
    if (step.state() != FlowStepState.WAITING) {
      throw new IllegalStateException("step is not WAITING: " + step.state());
    }
    Map<String, Object> out = outputs == null ? Map.of() : outputs;
    step = store.saveStep(step.withState(FlowStepState.SUCCEEDED, now, null, FlowJson.write(out)));
    Map<String, Object> context = FlowJson.readMap(run.contextJson());
    mergeOutputs(context, nodeId, out);
    run =
        store.saveRun(
            run.withContext(FlowJson.write(context), now)
                .withState(FlowRunState.RUNNING, now, null));
    return advance(run.id());
  }

  public Optional<FlowRun> findRun(String runId) {
    return store.findRun(runId);
  }

  public List<FlowStep> listSteps(String runId) {
    return store.listSteps(runId);
  }

  public List<FlowRun> listWaiting() {
    return store.listRunsByState(FlowRunState.WAITING);
  }

  private FlowRun advance(String runId) {
    FlowRun run = store.findRun(runId).orElseThrow();
    FlowDefinition definition = FlowMarkdown.parse(run.definitionMarkdown());
    int guard = 0;
    while (!run.state().terminal()
        && run.state() != FlowRunState.WAITING
        && guard++ < MAX_STEPS_PER_ADVANCE) {
      Instant now = clock.instant();
      if (timedOut(definition, run, now)) {
        run =
            store.saveRun(
                run.withState(FlowRunState.FAILED, now, "flow budget maxDurationSeconds exceeded"));
        break;
      }
      String nodeId = run.currentNodeId();
      if (nodeId == null || nodeId.isBlank()) {
        run = store.saveRun(run.withState(FlowRunState.SUCCEEDED, now));
        break;
      }
      FlowNode node = definition.nodes().get(nodeId);
      if (node == null) {
        run = store.saveRun(run.withState(FlowRunState.FAILED, now, "unknown node: " + nodeId));
        break;
      }

      String idem = FlowStep.idempotencyKeyFor(run.id(), nodeId);
      Optional<FlowStep> existing = store.findStepByIdempotencyKey(idem);
      if (existing.isPresent() && existing.get().state().succeeded()) {
        // Idempotent skip — do not re-execute successful nodes
        Map<String, Object> priorOut = FlowJson.readMap(existing.get().outputsJson());
        String next = pickNext(definition, run, node, priorOut);
        if (next == null) {
          run =
              store.saveRun(run.withCurrentNode(null, now).withState(FlowRunState.SUCCEEDED, now));
        } else {
          markSkippedBranches(definition, run, node, next, now);
          run = store.saveRun(run.withCurrentNode(next, now));
        }
        continue;
      }

      Map<String, Object> context = FlowJson.readMap(run.contextJson());
      Map<String, Object> runInputs = FlowJson.readMap(run.inputsJson());
      final String runIdCapture = run.id();
      Map<String, Object> resolved;
      try {
        resolved = resolveInputs(node, context, runInputs);
      } catch (RuntimeException ex) {
        String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        FlowStep failStep =
            existing.orElseGet(
                () ->
                    new FlowStep(
                        "fs-" + UUID.randomUUID().toString().replace("-", ""),
                        runIdCapture,
                        nodeId,
                        node.type(),
                        FlowStepState.FAILED,
                        0,
                        idem,
                        "{}",
                        "{}",
                        msg,
                        now,
                        now,
                        now,
                        now));
        store.saveStep(failStep.withState(FlowStepState.FAILED, now, msg, "{}"));
        run = store.saveRun(run.withState(FlowRunState.FAILED, now, msg));
        break;
      }
      final Map<String, Object> resolvedInputs = resolved;

      FlowStep step =
          existing.orElseGet(
              () ->
                  new FlowStep(
                      "fs-" + UUID.randomUUID().toString().replace("-", ""),
                      runIdCapture,
                      nodeId,
                      node.type(),
                      FlowStepState.PENDING,
                      0,
                      idem,
                      FlowJson.write(resolvedInputs),
                      "{}",
                      null,
                      null,
                      null,
                      now,
                      now));
      if (existing.isPresent()) {
        step =
            step.withInputs(FlowJson.write(resolvedInputs), now)
                .withAttempt(step.attempt() + 1, now);
      }
      step = store.saveStep(step.withState(FlowStepState.RUNNING, now, null, step.outputsJson()));

      FlowNodeOutcome outcome;
      try {
        outcome = handler.execute(node, resolvedInputs, run);
      } catch (RuntimeException ex) {
        outcome =
            FlowNodeOutcome.failed(
                ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
      }

      if (outcome.waiting()) {
        step =
            store.saveStep(
                step.withState(
                    FlowStepState.WAITING, now, null, FlowJson.write(outcome.outputs())));
        run =
            store.saveRun(
                run.withCurrentNode(nodeId, now).withState(FlowRunState.WAITING, now, null));
        LOG.info("flow run waiting id={} node={}", run.id(), nodeId);
        break;
      }

      if (outcome.failed()) {
        step =
            store.saveStep(
                step.withState(
                    FlowStepState.FAILED, now, outcome.error(), FlowJson.write(outcome.outputs())));
        if (step.attempt() < defaultMaxRetries) {
          // leave current node; loop will retry
          run = store.saveRun(run.withAttempt(run.attempt() + 1, now));
          continue;
        }
        run = store.saveRun(run.withState(FlowRunState.FAILED, now, outcome.error()));
        break;
      }

      // succeeded
      step =
          store.saveStep(
              step.withState(
                  FlowStepState.SUCCEEDED, now, null, FlowJson.write(outcome.outputs())));
      mergeOutputs(context, nodeId, outcome.outputs());
      String next = pickNext(definition, run, node, outcome.outputs());
      if (next == null) {
        run =
            store.saveRun(
                run.withContext(FlowJson.write(context), now)
                    .withCurrentNode(null, now)
                    .withState(FlowRunState.SUCCEEDED, now, null));
      } else {
        // mark untaken branch targets as SKIPPED when siblings diverge
        markSkippedBranches(definition, run, node, next, now);
        run =
            store.saveRun(run.withContext(FlowJson.write(context), now).withCurrentNode(next, now));
      }
    }
    return store.findRun(runId).orElseThrow();
  }

  private void markSkippedBranches(
      FlowDefinition definition, FlowRun run, FlowNode from, String taken, Instant now) {
    List<FlowEdge> outs = outgoing(definition, from.id());
    for (FlowEdge edge : outs) {
      if (edge.to().equals(taken)) {
        continue;
      }
      String key = FlowStep.idempotencyKeyFor(run.id(), edge.to());
      if (store.findStepByIdempotencyKey(key).isPresent()) {
        continue;
      }
      FlowNode skippedNode = definition.nodes().get(edge.to());
      if (skippedNode == null) {
        continue;
      }
      FlowStep skipped =
          new FlowStep(
              "fs-" + UUID.randomUUID().toString().replace("-", ""),
              run.id(),
              edge.to(),
              skippedNode.type(),
              FlowStepState.SKIPPED,
              0,
              key,
              "{}",
              "{}",
              "branch not taken",
              now,
              now,
              now,
              now);
      store.saveStep(skipped);
    }
  }

  private static boolean timedOut(FlowDefinition definition, FlowRun run, Instant now) {
    Integer max = definition.budget() == null ? null : definition.budget().maxDurationSeconds();
    if (max == null || max <= 0) {
      return false;
    }
    return now.isAfter(run.createdAt().plusSeconds(max.longValue()));
  }

  private static Map<String, Object> resolveInputs(
      FlowNode node, Map<String, Object> context, Map<String, Object> runInputs) {
    Map<String, Object> resolved = new LinkedHashMap<>();
    for (Map.Entry<String, FlowPort> e : node.inputs().entrySet()) {
      FlowPort port = e.getValue();
      Optional<FlowPort.WireRef> wire = port.wire();
      if (wire.isPresent()) {
        String key = wire.get().nodeId() + "." + wire.get().portName();
        if (context.containsKey(key)) {
          resolved.put(e.getKey(), context.get(key));
        } else if (port.required()) {
          throw new IllegalStateException("missing wired input " + key + " for " + node.id());
        }
      } else if (runInputs.containsKey(e.getKey())) {
        resolved.put(e.getKey(), runInputs.get(e.getKey()));
      } else if (runInputs.containsKey(node.id() + "." + e.getKey())) {
        resolved.put(e.getKey(), runInputs.get(node.id() + "." + e.getKey()));
      } else if (port.required()) {
        throw new IllegalStateException(
            "missing required input " + e.getKey() + " for " + node.id());
      }
    }
    // allow undeclared run inputs passthrough for entry convenience
    if (resolved.isEmpty() && !runInputs.isEmpty() && node.inputs().isEmpty()) {
      resolved.putAll(runInputs);
    }
    return resolved;
  }

  private static void mergeOutputs(
      Map<String, Object> context, String nodeId, Map<String, Object> outputs) {
    if (outputs == null) {
      return;
    }
    for (Map.Entry<String, Object> e : outputs.entrySet()) {
      context.put(nodeId + "." + e.getKey(), e.getValue());
    }
  }

  private String pickNext(
      FlowDefinition definition, FlowRun run, FlowNode from, Map<String, Object> nodeOutputs) {
    Map<String, Object> context = FlowJson.readMap(run.contextJson());
    mergeOutputs(context, from.id(), nodeOutputs);
    List<FlowEdge> outs = outgoing(definition, from.id());
    if (outs.isEmpty()) {
      return null;
    }
    List<FlowEdge> matched = new ArrayList<>();
    for (FlowEdge edge : outs) {
      if (FlowBranchPredicates.matches(edge.when(), context, nodeOutputs)) {
        matched.add(edge);
      }
    }
    if (matched.isEmpty()) {
      // no predicate matched — if any unconditional edge exists it would already be in matched;
      // treat as end
      return null;
    }
    return matched.get(0).to();
  }

  private static List<FlowEdge> outgoing(FlowDefinition definition, String from) {
    List<FlowEdge> outs = new ArrayList<>();
    for (FlowEdge edge : definition.edges()) {
      if (edge.from().equals(from)) {
        outs.add(edge);
      }
    }
    return outs;
  }

  private void requireEnabled() {
    if (!enabled) {
      throw new IllegalStateException("oryxos.flow.engine-enabled is false");
    }
  }
}
