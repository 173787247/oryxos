package io.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.eval.EvalExportService;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Export audit traces as offline eval suite JSON (048 follow-on / #695).
 *
 * <p>出网的是 Jackson 3（{@code tools.jackson}）的树：Boot 4 / Spring 7 的 MVC JSON 转换器只认这个类型，Jackson 2 的
 * JsonNode 会被当成 POJO 序列化成 {@code {"array":false,"nodeType":"OBJECT",…}}，suite 内容全丢。
 */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "Spring MVC injects export service; same pattern as AuditApiController.")
@RestController
@RequestMapping("/api/v1/evals")
public class EvalExportApiController {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final EvalExportService exportService;

  public EvalExportApiController(EvalExportService exportService) {
    this.exportService = Objects.requireNonNull(exportService);
  }

  @GetMapping("/export/trace/{traceId}")
  public ApiResponse<JsonNode> exportTrace(@PathVariable("traceId") String traceId) {
    try {
      return ApiResponse.ok(MAPPER.readTree(exportService.exportTraceSuite(traceId)));
    } catch (JacksonException e) {
      throw new IllegalStateException("eval suite JSON parse failed: " + e.getMessage(), e);
    }
  }
}
