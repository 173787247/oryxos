package io.oryxos.web.eval;

import io.oryxos.core.eval.AuditEvalExporter;
import io.oryxos.core.eval.AuditTraceObservation;
import io.oryxos.storage.LlmCall;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.storage.ToolInvocation;
import io.oryxos.storage.ToolInvocationRepository;
import io.oryxos.web.error.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Builds offline eval suite JSON from audit rows for one {@code trace_id} (048 follow-on). */
@org.springframework.stereotype.Service
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-injected repositories; shared singleton is intentional.")
public class EvalExportService {

  private final LlmCallRepository llmCallRepository;
  private final ToolInvocationRepository toolInvocationRepository;

  public EvalExportService(
      LlmCallRepository llmCallRepository, ToolInvocationRepository toolInvocationRepository) {
    this.llmCallRepository = Objects.requireNonNull(llmCallRepository);
    this.toolInvocationRepository = Objects.requireNonNull(toolInvocationRepository);
  }

  /** Suite JSON for one trace; not found when neither llm nor tool rows exist. */
  public String exportTraceSuite(String traceId) {
    if (traceId == null || traceId.isBlank()) {
      throw new IllegalArgumentException("traceId must not be blank");
    }
    String id = traceId.strip();
    List<LlmCall> llms = llmCallRepository.findByTraceId(id);
    List<ToolInvocation> tools = toolInvocationRepository.findByTraceId(id);
    if (llms.isEmpty() && tools.isEmpty()) {
      throw new ResourceNotFoundException("audit trace not found: " + id);
    }
    AuditTraceObservation obs = toObservation(id, llms, tools);
    return AuditEvalExporter.toSuiteJson(
        "audit-trace-" + AuditEvalExporter.sanitizeId(id), List.of(obs));
  }

  static AuditTraceObservation toObservation(
      String traceId, List<LlmCall> llms, List<ToolInvocation> tools) {
    List<ToolInvocation> ordered =
        tools.stream().sorted(Comparator.comparing(ToolInvocation::getCreatedAt)).toList();
    List<String> toolNames = new ArrayList<>();
    for (ToolInvocation t : ordered) {
      if (t.getToolName() != null && !t.getToolName().isBlank()) {
        toolNames.add(t.getToolName());
      }
    }
    boolean toolsOk = ordered.stream().allMatch(ToolInvocation::isSuccess);
    boolean llmsOk = llms.stream().allMatch(LlmCall::isSuccess);
    boolean success = toolsOk && llmsOk;
    long toolLatency = ordered.stream().mapToLong(ToolInvocation::getDurationMs).sum();
    long llmLatency = llms.stream().mapToLong(LlmCall::getDurationMs).sum();
    long latencyMs = Math.max(toolLatency, llmLatency);
    long costMicros =
        llms.stream()
            .filter(c -> c.getCostMicros() != null)
            .mapToLong(LlmCall::getCostMicros)
            .sum();
    String agent = firstProfile(ordered, llms);
    return new AuditTraceObservation(traceId, agent, success, toolNames, latencyMs, costMicros);
  }

  private static String firstProfile(List<ToolInvocation> tools, List<LlmCall> llms) {
    for (ToolInvocation t : tools) {
      if (t.getProfileName() != null && !t.getProfileName().isBlank()) {
        return t.getProfileName().strip();
      }
    }
    for (LlmCall c : llms) {
      if (c.getProfileName() != null && !c.getProfileName().isBlank()) {
        return c.getProfileName().strip();
      }
    }
    return "unknown-agent";
  }
}
