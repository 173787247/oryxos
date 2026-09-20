package io.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.core.routing.CandidateDisposition;
import io.oryxos.core.routing.ModelRoutingService;
import io.oryxos.core.routing.RoutingCandidate;
import io.oryxos.core.routing.RoutingDecision;
import io.oryxos.core.routing.RoutingReason;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.error.ResourceNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Explainable routing decision API (#477). 404 when oryxos.routing.enabled=false. */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "core-stage web API; flag-off returns 404.")
@RestController
@RequestMapping("/api/v1/routing")
public class RoutingApiController {

  private final ModelRoutingService routing;

  public RoutingApiController(ModelRoutingService routing) {
    this.routing = routing;
  }

  @GetMapping("/decisions")
  public ApiResponse<Map<String, Object>> decisions(
      @RequestParam(required = false) String runId,
      @RequestParam(required = false, defaultValue = "20") int limit) {
    requireEnabled();
    var list =
        runId != null && !runId.isBlank()
            ? routing.findByRunId(runId)
            : routing.recent(Math.min(100, Math.max(1, limit)));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("decisions", list.stream().map(RoutingApiController::toView).toList());
    return ApiResponse.ok(body);
  }

  private void requireEnabled() {
    if (routing == null || !routing.isEnabled()) {
      throw new ResourceNotFoundException("routing disabled");
    }
  }

  private static Map<String, Object> toView(RoutingDecision d) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", d.id());
    m.put("runId", d.runId());
    m.put("agentName", d.agentName());
    m.put("selectedProvider", d.selectedProvider());
    m.put("selectedModel", d.selectedModel());
    m.put("reasons", d.reasons().stream().map(RoutingApiController::reasonView).toList());
    m.put("candidates", d.candidates().stream().map(RoutingApiController::candView).toList());
    m.put(
        "attemptOrder", d.attemptOrder().stream().map(RoutingApiController::attemptView).toList());
    m.put("createdAt", d.createdAt() == null ? null : d.createdAt().toString());
    return m;
  }

  private static Map<String, Object> reasonView(RoutingReason r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("code", r.code().name());
    m.put("detail", r.detail());
    return m;
  }

  private static Map<String, Object> candView(CandidateDisposition c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("provider", c.provider());
    m.put("model", c.model());
    m.put("status", c.status());
    m.put("reason", reasonView(c.reason()));
    return m;
  }

  private static Map<String, Object> attemptView(RoutingCandidate c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("provider", c.provider());
    m.put("model", c.model());
    return m;
  }
}
