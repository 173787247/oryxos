package io.oryxos.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.core.a2a.A2aMessageService;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A2A JSON-RPC endpoint (v0.3 {@code message/send}). Bare JSON-RPC response — not ApiResponse
 * wrapper.
 */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "Spring MVC injects message service; A2A JSON-RPC is intentionally public.")
@RestController
@RequestMapping("/api/v1/a2a")
@ConditionalOnBean(A2aMessageService.class)
public class A2aJsonRpcController {

  private final A2aMessageService messageService;

  public A2aJsonRpcController(A2aMessageService messageService) {
    this.messageService = Objects.requireNonNull(messageService, "messageService");
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ObjectNode handle(@RequestBody JsonNode body) {
    return messageService.handle(body);
  }
}
