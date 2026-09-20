package io.oryxos.core.flow;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Durable Markdown Flow execution engine (046 / #468 + 047 / #469): validate → persist → execute →
 * resume; HUMAN/APPROVAL wait/timeout/cancel; optional compensate on failure; timeline replay.
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
  private final boolean compensationEnabled;

  public FlowEngine(FlowRunStore store, FlowNodeHandler handler, Clock clock, boolean enabled) {
    this(store, handler, clock, enabled, 0, false);
  }

  public FlowEngine(
      FlowRunStore store,
      FlowNodeHandler handler,
      Clock clock,
      boolean enabled,
      int defaultMaxRetries) {
    this(store, handler, clock, enabled, defaultMaxRetries, false);
  }

  public FlowEngine(
      FlowRunStore store,
      FlowNodeHandler handler,
      Clock clock,
      boolean enabled,
      int defaultMaxRetries,
      boolean compensationEnabled) {
    this.store = Objects.requireNonNull(store, "store");
    this.handler = Objects.requireNonNull(handler, "handler");
    this.clock = clock == null ? Clock.systemUTC() : clock;
    this.enabled = enabled;
    this.defaultMaxRetries = Math.max(0, defaultMaxRetries);
    this.compensationEnabled = compensationEnabled;
  }

  public boolean enabled() {
    return enabled;
  }

  public boolean compensationEnabled() {
    return compensationEnabled;
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
   * Resume a RUNNING or WAITING run after process restart. WAITING with an expired deadline is
   * auto-cancelled (#469); otherwise WAITING needs {@link #completeWaiting}.
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
      FlowRun expired = expireWaiting(runId);
      if (expired.state().terminal()) {
        return expired;
      }
      return expired;
    }
    if (run.state() == FlowRunState.QUEUED) {
      Instant now = clock.instant();
      run = store.saveRun(run.withState(FlowRunState.RUNNING, now));
    }
    return advance(run.id());
  }

  /**
   * Complete a WAITING human/approval step with outputs, then continue. Rejects expired waits
   * (mirrors #466 decide safety).
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
    if (step.expiredAt(now)) {
      return expireWaiting(runId);
    }
    Map<String, Object> out = outputs == null ? Map.of() : outputs;
    store.saveStep(step.withState(FlowStepState.SUCCEEDED, now, null, FlowJson.write(out)));
    Map<String, Object> context = FlowJson.readMap(run.contextJson());
    mergeOutputs(context, nodeId, out);
    store.saveRun(
        run.withContext(FlowJson.write(context), now).withState(FlowRunState.RUNNING, now, null));
    return advance(runId);
  }

  /**
   * Cancel a WAITING human/approval run. Idempotent when already terminal. Mirrors #466 cancel
   * semantics at the Flow layer.
   */
  public FlowRun cancelWaiting(String runId, String reason) {
    requireEnabled();
    Instant now = clock.instant();
    FlowRun run =
        store
            .findRun(runId)
            .orElseThrow(() -> new IllegalArgumentException("unknown run: " + runId));
    if (run.state().terminal()) {
      return run;
    }
    if (run.state() != FlowRunState.WAITING) {
      throw new IllegalStateException("run is not WAITING: " + run.state());
    }
    String msg = reason == null || reason.isBlank() ? "waiting cancelled" : reason.strip();
    return cancelWaitingInternal(run, msg, now);
  }

  /**
   * If the current WAITING step has passed {@code expiresAt}, cancel it with a timeout error;
   * otherwise return the current snapshot unchanged.
   */
  public FlowRun expireWaiting(String runId) {
    requireEnabled();
    Instant now = clock.instant();
    FlowRun run =
        store
            .findRun(runId)
            .orElseThrow(() -> new IllegalArgumentException("unknown run: " + runId));
    if (run.state() != FlowRunState.WAITING) {
      return run;
    }
    String nodeId = run.currentNodeId();
    if (nodeId == null || nodeId.isBlank()) {
      return run;
    }
    Optional<FlowStep> stepOpt =
        store.findStepByIdempotencyKey(FlowStep.idempotencyKeyFor(runId, nodeId));
    if (stepOpt.isEmpty() || !stepOpt.get().expiredAt(now)) {
      return run;
    }
    return cancelWaitingInternal(
        run, "human/approval wait timed out at " + stepOpt.get().expiresAt(), now);
  }

  /** Full node timeline for replay (ordered by event time). */
  public List<FlowTimelineEvent> timeline(String runId) {
    requireEnabled();
    List<FlowStep> steps = store.listSteps(runId);
    List<FlowTimelineEvent> events = new ArrayList<>();
    for (FlowStep step : steps) {
      Instant start = step.startedAt() != null ? step.startedAt() : step.createdAt();
      events.add(
          new FlowTimelineEvent(
              start,
              step.runId(),
              step.id(),
              step.nodeId(),
              step.nodeType(),
              step.state() == FlowStepState.PENDING ? FlowStepState.PENDING : FlowStepState.RUNNING,
              null,
              "step entered"));
      Instant end = step.finishedAt() != null ? step.finishedAt() : step.updatedAt();
      String detail = timelineDetail(step);
      events.add(
          new FlowTimelineEvent(
              end,
              step.runId(),
              step.id(),
              step.nodeId(),
              step.nodeType(),
              step.state(),
              step.error(),
              detail));
    }
    events.sort(
        Comparator.comparing(FlowTimelineEvent::at)
            .thenComparing(FlowTimelineEvent::nodeId)
            .thenComparing(FlowTimelineEvent::stepId));
    return List.copyOf(events);
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

  private FlowRun cancelWaitingInternal(FlowRun run, String message, Instant now) {
    String nodeId = run.currentNodeId();
    if (nodeId != null && !nodeId.isBlank()) {
      Optional<FlowStep> stepOpt =
          store.findStepByIdempotencyKey(FlowStep.idempotencyKeyFor(run.id(), nodeId));
      if (stepOpt.isPresent() && stepOpt.get().state() == FlowStepState.WAITING) {
        store.saveStep(
            stepOpt
                .get()
                .withState(FlowStepState.CANCELLED, now, message, stepOpt.get().outputsJson()));
      }
    }
    Optional<FlowRun> moved =
        store.tryTransitionRun(
            run.id(), FlowRunState.WAITING, run.withState(FlowRunState.CANCELLED, now, message));
    FlowRun finalRun =
        moved.orElseGet(
            () ->
                store
                    .findRun(run.id())
                    .orElse(run.withState(FlowRunState.CANCELLED, now, message)));
    LOG.info("flow run cancelled id={} reason={}", finalRun.id(), message);
    return finalRun;
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
                        null,
                        now,
                        now));
        store.saveStep(failStep.withState(FlowStepState.FAILED, now, msg, "{}"));
        run = failRunAfterNode(definition, run, node, now, msg);
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
        Instant expires = null;
        if (node.timeoutSeconds() != null && node.timeoutSeconds() > 0) {
          expires = now.plusSeconds(node.timeoutSeconds().longValue());
        }
        FlowStep waiting =
            step.withState(FlowStepState.WAITING, now, null, FlowJson.write(outcome.outputs()));
        if (expires != null) {
          waiting = waiting.withExpiresAt(expires, now);
        }
        store.saveStep(waiting);
        run =
            store.saveRun(
                run.withCurrentNode(nodeId, now).withState(FlowRunState.WAITING, now, null));
        LOG.info("flow run waiting id={} node={} expiresAt={}", run.id(), nodeId, expires);
        break;
      }

      if (outcome.failed()) {
        step =
            store.saveStep(
                step.withState(
                    FlowStepState.FAILED, now, outcome.error(), FlowJson.write(outcome.outputs())));
        if (step.attempt() < defaultMaxRetries) {
          run = store.saveRun(run.withAttempt(run.attempt() + 1, now));
          continue;
        }
        run = failRunAfterNode(definition, run, node, now, outcome.error());
        break;
      }

      store.saveStep(
          step.withState(FlowStepState.SUCCEEDED, now, null, FlowJson.write(outcome.outputs())));
      mergeOutputs(context, nodeId, outcome.outputs());
      String next = pickNext(definition, run, node, outcome.outputs());
      if (next == null) {
        run =
            store.saveRun(
                run.withContext(FlowJson.write(context), now)
                    .withCurrentNode(null, now)
                    .withState(FlowRunState.SUCCEEDED, now, null));
      } else {
        markSkippedBranches(definition, run, node, next, now);
        run =
            store.saveRun(run.withContext(FlowJson.write(context), now).withCurrentNode(next, now));
      }
    }
    return run;
  }

  private FlowRun failRunAfterNode(
      FlowDefinition definition, FlowRun run, FlowNode failedNode, Instant now, String error) {
    if (compensationEnabled && failedNode.compensate() != null) {
      runCompensate(definition, run, failedNode, now);
      run = store.findRun(run.id()).orElse(run);
    }
    return store.saveRun(run.withState(FlowRunState.FAILED, now, error));
  }

  private void runCompensate(
      FlowDefinition definition, FlowRun run, FlowNode failedNode, Instant now) {
    String targetId = failedNode.compensate();
    FlowNode target = definition.nodes().get(targetId);
    if (target == null) {
      LOG.warn(
          "compensate target missing run={} node={} target={}",
          run.id(),
          failedNode.id(),
          targetId);
      return;
    }
    String key = FlowStep.compensateIdempotencyKey(run.id(), failedNode.id());
    if (store.findStepByIdempotencyKey(key).isPresent()) {
      return;
    }
    Map<String, Object> context = FlowJson.readMap(run.contextJson());
    Map<String, Object> runInputs = FlowJson.readMap(run.inputsJson());
    Map<String, Object> resolved;
    try {
      resolved = resolveInputs(target, context, runInputs);
    } catch (RuntimeException ex) {
      resolved = Map.of();
    }

    FlowStep step =
        new FlowStep(
            "fs-" + UUID.randomUUID().toString().replace("-", ""),
            run.id(),
            target.id(),
            target.type(),
            FlowStepState.RUNNING,
            0,
            key,
            FlowJson.write(resolved),
            "{}",
            null,
            now,
            null,
            null,
            now,
            now);
    store.saveStep(step);

    FlowNodeOutcome outcome;
    try {
      outcome = handler.execute(target, resolved, run);
    } catch (RuntimeException ex) {
      outcome =
          FlowNodeOutcome.failed(
              ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }
    Map<String, Object> out = new LinkedHashMap<>(outcome.outputs());
    out.put("__compensatesFor", failedNode.id());
    if (outcome.failed()) {
      store.saveStep(
          step.withState(FlowStepState.FAILED, now, outcome.error(), FlowJson.write(out)));
    } else {
      store.saveStep(step.withState(FlowStepState.SUCCEEDED, now, null, FlowJson.write(out)));
      mergeOutputs(context, target.id(), out);
      store.saveRun(run.withContext(FlowJson.write(context), now));
    }
    LOG.info(
        "flow compensate run={} failedNode={} compensate={} state={}",
        run.id(),
        failedNode.id(),
        target.id(),
        outcome.failed() ? "FAILED" : "SUCCEEDED");
  }

  private static String timelineDetail(FlowStep step) {
    if (step.state() == FlowStepState.WAITING) {
      return step.expiresAt() == null ? "waiting" : "waiting until " + step.expiresAt();
    }
    if (step.state() == FlowStepState.CANCELLED) {
      return "cancelled";
    }
    Map<String, Object> outs = FlowJson.readMap(step.outputsJson());
    Object forNode = outs.get("__compensatesFor");
    if (forNode != null) {
      return "compensate for " + forNode;
    }
    return step.state().name().toLowerCase(Locale.ROOT);
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
              null,
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
      if (e.getKey().startsWith("__")) {
        continue;
      }
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
