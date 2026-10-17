package io.oryxos.web.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.core.a2a.A2aJsonRpcError;
import io.oryxos.core.a2a.A2aMessageService;
import io.oryxos.web.config.WebSseProperties;
import io.oryxos.web.sse.SseWriter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A2A JSON-RPC endpoint (v0.3 {@code message/send} + {@code message/stream}). Bare JSON-RPC — not
 * ApiResponse wrapper. Stream uses sync {@link SseWriter} (constitution VII).
 *
 * <p>The request body is read as a {@code String} and the response is written as a {@code String}
 * on purpose. This application runs on Spring Boot 4 / Spring 7, whose MVC JSON converter is
 * Jackson 3 ({@code tools.jackson}); the A2A service layer speaks Jackson 2 nodes ({@code
 * com.fasterxml.jackson.databind.JsonNode}). Binding {@code @RequestBody JsonNode} with the Jackson
 * 3 converter fails at bind time — `Cannot construct instance of
 * com.fasterxml.jackson.databind.JsonNode (no Creators ...)` — and every inbound A2A call became an
 * HTTP 500 before the handler ran. The version bridge therefore belongs at the HTTP boundary: take
 * the raw JSON text, parse/serialize it with the Jackson 2 {@code ObjectMapper}, and hand the tree
 * to the service layer unchanged. Migrating the core A2A services to Jackson 3, or switching the
 * whole application to the deprecated {@code
 * spring.http.converters.preferred-json-mapper=jackson2}, would be far wider changes than this one
 * endpoint needs.
 */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "Spring MVC injects message service / SSE props / ObjectMapper; A2A JSON-RPC is intentionally public.")
@RestController
@RequestMapping("/api/v1/a2a")
// 与它所依赖的 bean 用【同一个】条件。
// @ConditionalOnBean 在这里永远为 false：组件扫描早于配置类处理，
// 求值时那个 bean 的定义还没注册（实测 0.1.6-RELEASE，四个端点全 404）。
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    prefix = "oryxos.a2a",
    name = "enabled",
    havingValue = "true")
public class A2aJsonRpcController {

  private final A2aMessageService messageService;
  private final WebSseProperties sseProperties;
  private final ObjectMapper objectMapper;

  public A2aJsonRpcController(
      A2aMessageService messageService, WebSseProperties sseProperties, ObjectMapper objectMapper) {
    this.messageService = Objects.requireNonNull(messageService, "messageService");
    this.sseProperties = Objects.requireNonNull(sseProperties, "sseProperties");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
  public ResponseEntity<String> handle(@RequestBody String body, HttpServletResponse response) {
    JsonNode request;
    try {
      request = objectMapper.readTree(body == null ? "" : body);
    } catch (JsonProcessingException parseFailure) {
      // JSON-RPC 2.0 parse error: the transport succeeded, the payload did not parse.
      return json(parseError());
    }
    String method =
        request != null && request.hasNonNull("method") ? request.get("method").asText("") : "";
    if (A2aMessageService.METHOD_MESSAGE_STREAM.equals(method)) {
      Optional<ObjectNode> early = messageService.validateStream(request);
      if (early.isPresent()) {
        return json(early.get());
      }
      try (SseWriter writer = new SseWriter(response, sseProperties.getHeartbeatSeconds())) {
        messageService.emitStream(request, frame -> writer.event("message", frame.toString()));
      } catch (IOException e) {
        throw new UncheckedIOException("failed to open A2A SSE stream", e);
      }
      // The SSE stream already owns the response; a null return means "no extra body".
      return null;
    }
    return json(messageService.handle(request));
  }

  /** Serialize with Jackson 2 deliberately: see the class comment on the version bridge. */
  private ResponseEntity<String> json(ObjectNode payload) {
    try {
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_JSON)
          .body(objectMapper.writeValueAsString(payload));
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException("failed to serialize A2A JSON-RPC response", e);
    }
  }

  private ObjectNode parseError() {
    ObjectNode response = objectMapper.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.putNull("id");
    ObjectNode error = response.putObject("error");
    error.put("code", A2aJsonRpcError.PARSE_ERROR);
    error.put("message", "Parse error");
    return response;
  }
}
