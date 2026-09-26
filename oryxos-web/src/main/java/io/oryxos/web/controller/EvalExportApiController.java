package io.oryxos.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.eval.EvalExportService;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Export audit traces as offline eval suite JSON (048 follow-on / #695). */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "Spring MVC injects export service; same pattern as AuditApiController.")
@RestController
@RequestMapping("/api/v1/evals")
public class EvalExportApiController {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final EvalExportService exportService;

  public EvalExportApiController(EvalExportService exportService) {
    this.exportService = Objects.requireNonNull(exportService);
  }

  @GetMapping("/export/trace/{traceId}")
  public ApiResponse<JsonNode> exportTrace(@PathVariable("traceId") String traceId) {
    try {
      return ApiResponse.ok(MAPPER.readTree(exportService.exportTraceSuite(traceId)));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("eval suite JSON parse failed: " + e.getMessage(), e);
    }
  }
}
