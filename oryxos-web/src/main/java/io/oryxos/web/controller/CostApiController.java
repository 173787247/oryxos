package io.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.core.cost.CostAttributionQuery;
import io.oryxos.core.cost.CostAttributionSummary;
import io.oryxos.core.cost.CostLedgerEntry;
import io.oryxos.core.cost.CostLedgerService;
import io.oryxos.core.cost.CostReconcileResult;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.error.ResourceNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Cost ledger query API (#476). All routes 404 when {@code oryxos.cost.enabled=false}. */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "core-stage web API; flag-off returns 404.")
@RestController
@RequestMapping("/api/v1/cost")
public class CostApiController {

  private final CostLedgerService ledger;

  public CostApiController(CostLedgerService ledger) {
    this.ledger = ledger;
  }

  @GetMapping("/attribution")
  public ApiResponse<Map<String, Object>> attribution(
      @RequestParam(required = false) String runId,
      @RequestParam(required = false) String taskId,
      @RequestParam(required = false) String agent,
      @RequestParam(required = false) String teamId,
      @RequestParam(required = false) String provider,
      @RequestParam(required = false) String model) {
    requireEnabled();
    CostAttributionSummary summary =
        ledger.query(new CostAttributionQuery(runId, taskId, agent, teamId, provider, model));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("llmCostMicros", summary.llmCostMicros());
    body.put("toolCostMicros", summary.toolCostMicros());
    body.put("totalCostMicros", summary.totalCostMicros());
    body.put("totalLatencyMs", summary.totalLatencyMs());
    body.put("promptTokens", summary.promptTokens());
    body.put("completionTokens", summary.completionTokens());
    body.put("priceVersions", summary.priceVersions());
    body.put("entries", summary.entries().stream().map(CostApiController::toView).toList());
    return ApiResponse.ok(body);
  }

  @GetMapping("/runs/{runId}/reconcile")
  public ApiResponse<Map<String, Object>> reconcile(@PathVariable String runId) {
    requireEnabled();
    CostReconcileResult r = ledger.reconcile(runId);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("runId", r.runId());
    body.put("ledgerLlmCostMicros", r.ledgerLlmCostMicros());
    body.put("ledgerToolCostMicros", r.ledgerToolCostMicros());
    body.put("auditLlmCostMicros", r.auditLlmCostMicros());
    body.put("matched", r.matched());
    body.put("detail", r.detail());
    return ApiResponse.ok(body);
  }

  private void requireEnabled() {
    if (ledger == null || !ledger.isEnabled()) {
      throw new ResourceNotFoundException("cost ledger disabled");
    }
  }

  private static Map<String, Object> toView(CostLedgerEntry e) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", e.id());
    m.put("runId", e.runId());
    m.put("taskId", e.taskId());
    m.put("agentName", e.agentName());
    m.put("teamId", e.teamId());
    m.put("provider", e.provider());
    m.put("model", e.model());
    m.put("sourceKind", e.sourceKind().name());
    m.put("sourceRef", e.sourceRef());
    m.put("promptTokens", e.promptTokens());
    m.put("completionTokens", e.completionTokens());
    m.put("totalTokens", e.totalTokens());
    m.put("llmCostMicros", e.llmCostMicros());
    m.put("toolCostMicros", e.toolCostMicros());
    m.put("latencyMs", e.latencyMs());
    m.put("priceVersion", e.priceVersion());
    m.put("sessionId", e.sessionId());
    m.put("traceId", e.traceId());
    m.put("createdAt", e.createdAt() == null ? null : e.createdAt().toString());
    return m;
  }
}
