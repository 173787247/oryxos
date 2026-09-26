package io.oryxos.core.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class A2aMessageServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("message/send routes text to metadata.agent")
  void messageSend_routes() throws Exception {
    AtomicReference<String> seenAgent = new AtomicReference<>();
    AtomicReference<String> seenMsg = new AtomicReference<>();
    A2aProperties props =
        new A2aProperties(
            true, "OryxOS", "d", "http://localhost:8080", "0.1.6", "0.3.0", "fallback");
    A2aMessageService svc =
        new A2aMessageService(
            props,
            (agent, msg) -> {
              seenAgent.set(agent);
              seenMsg.set(msg);
              return "pong:" + msg;
            },
            () -> List.of(new A2aAgentRef("writer", "drafts"), new A2aAgentRef("fallback", "")));

    ObjectNode req = MAPPER.createObjectNode();
    req.put("jsonrpc", "2.0");
    req.put("id", 1);
    req.put("method", "message/send");
    ObjectNode params = req.putObject("params");
    ObjectNode message = params.putObject("message");
    message.put("role", "user");
    message.put("messageId", "m1");
    ObjectNode part = message.putArray("parts").addObject();
    part.put("kind", "text");
    part.put("text", "hello");
    params.putObject("metadata").put("agent", "writer");

    ObjectNode resp = svc.handle(req);
    assertEquals("2.0", resp.get("jsonrpc").asText());
    assertEquals(1, resp.get("id").asInt());
    assertFalse(resp.has("error"));
    assertEquals("agent", resp.path("result").path("role").asText());
    assertEquals("pong:hello", resp.path("result").path("parts").get(0).path("text").asText());
    assertEquals("writer", seenAgent.get());
    assertEquals("hello", seenMsg.get());
  }

  @Test
  @DisplayName("unknown method returns -32601")
  void unknownMethod() {
    A2aMessageService svc =
        new A2aMessageService(A2aProperties.disabled(), (a, m) -> "x", List::of);
    ObjectNode req = MAPPER.createObjectNode();
    req.put("jsonrpc", "2.0");
    req.put("id", "abc");
    req.put("method", "tasks/get");
    ObjectNode resp = svc.handle(req);
    assertEquals(A2aJsonRpcError.METHOD_NOT_FOUND, resp.path("error").path("code").asInt());
  }

  @Test
  @DisplayName("default-agent used when metadata omitted")
  void defaultAgent() {
    A2aProperties props =
        new A2aProperties(true, "OryxOS", "d", "http://localhost:8080", "0.1.6", "0.3.0", "writer");
    A2aMessageService svc =
        new A2aMessageService(
            props, (a, m) -> a + ":" + m, () -> List.of(new A2aAgentRef("writer", "")));
    ObjectNode req = MAPPER.createObjectNode();
    req.put("jsonrpc", "2.0");
    req.put("id", 2);
    req.put("method", "message/send");
    ObjectNode message = req.putObject("params").putObject("message");
    message.put("role", "user");
    message.putArray("parts").addObject().put("kind", "text").put("text", "hi");
    ObjectNode resp = svc.handle(req);
    assertEquals("writer:hi", resp.path("result").path("parts").get(0).path("text").asText());
  }

  @Test
  @DisplayName("unknown agent rejected when catalog non-empty")
  void unknownAgent() {
    A2aProperties props =
        new A2aProperties(true, "OryxOS", "d", "http://localhost:8080", "0.1.6", "0.3.0", "");
    A2aMessageService svc =
        new A2aMessageService(props, (a, m) -> "x", () -> List.of(new A2aAgentRef("writer", "")));
    ObjectNode req = MAPPER.createObjectNode();
    req.put("jsonrpc", "2.0");
    req.put("id", 3);
    req.put("method", "message/send");
    ObjectNode params = req.putObject("params");
    params.putObject("message").putArray("parts").addObject().put("kind", "text").put("text", "x");
    params.putObject("metadata").put("agent", "nope");
    ObjectNode resp = svc.handle(req);
    assertEquals(A2aJsonRpcError.INVALID_PARAMS, resp.path("error").path("code").asInt());
    assertTrue(resp.path("error").path("message").asText().contains("unknown agent"));
  }
}
