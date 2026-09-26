package io.oryxos.core.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Handles A2A JSON-RPC {@code message/send} and {@code message/stream}: extract text parts, route
 * to a local agent via {@code processStateless}. Stream emits one Message result then closes (A2A
 * Message-only stream). Tasks / push / cross-node are out of scope.
 */
public final class A2aMessageService {

  public static final String METHOD_MESSAGE_SEND = "message/send";
  public static final String METHOD_MESSAGE_STREAM = "message/stream";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final A2aProperties properties;
  private final BiFunction<String, String, String> agentRunner;
  private final Supplier<List<A2aAgentRef>> agents;

  public A2aMessageService(
      A2aProperties properties,
      BiFunction<String, String, String> agentRunner,
      Supplier<List<A2aAgentRef>> agents) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.agentRunner = Objects.requireNonNull(agentRunner, "agentRunner");
    this.agents = Objects.requireNonNull(agents, "agents");
  }

  /**
   * @param request JSON-RPC 2.0 request node
   * @return JSON-RPC 2.0 response node (result or error)
   */
  public ObjectNode handle(JsonNode request) {
    Prepared prepared = prepare(request, METHOD_MESSAGE_SEND);
    if (prepared.error() != null) {
      return prepared.error();
    }
    return runSend(prepared);
  }

  /**
   * Pre-flight for {@code message/stream}: JSON-RPC error if invalid (caller must not open SSE).
   * Empty means validation passed.
   */
  public Optional<ObjectNode> validateStream(JsonNode request) {
    Prepared prepared = prepare(request, METHOD_MESSAGE_STREAM);
    return prepared.error() != null ? Optional.of(prepared.error()) : Optional.empty();
  }

  /**
   * Emit SSE frames for a validated {@code message/stream} request. One Message result (A2A
   * Message-only stream), or a JSON-RPC error frame if the agent fails after SSE started.
   */
  public void emitStream(JsonNode request, Consumer<ObjectNode> emit) {
    Objects.requireNonNull(emit, "emit");
    Prepared prepared = prepare(request, METHOD_MESSAGE_STREAM);
    if (prepared.error() != null) {
      emit.accept(prepared.error());
      return;
    }
    emit.accept(runSend(prepared));
  }

  /**
   * Validate + run {@code message/stream} without HTTP: empty Optional and one emitted frame on
   * success; present Optional is a pre-emit JSON-RPC error.
   */
  public Optional<ObjectNode> stream(JsonNode request, Consumer<ObjectNode> emit) {
    Optional<ObjectNode> early = validateStream(request);
    if (early.isPresent()) {
      return early;
    }
    emitStream(request, emit);
    return Optional.empty();
  }

  private ObjectNode runSend(Prepared prepared) {
    ObjectNode response = prepared.response();
    try {
      String reply = agentRunner.apply(prepared.agent(), prepared.userText());
      if (reply == null) {
        reply = "";
      }
      response.set("result", agentMessage(reply));
      return response;
    } catch (RuntimeException e) {
      String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      response.set("error", errorNode(A2aJsonRpcError.INTERNAL_ERROR, msg));
      return response;
    }
  }

  private Prepared prepare(JsonNode request, String expectedMethod) {
    ObjectNode response = MAPPER.createObjectNode();
    response.put("jsonrpc", "2.0");
    if (request == null || !request.isObject()) {
      response.putNull("id");
      response.set(
          "error", errorNode(A2aJsonRpcError.INVALID_REQUEST, "request must be a JSON object"));
      return Prepared.error(response);
    }
    copyId(request, response);
    String jsonrpc = text(request, "jsonrpc");
    if (!"2.0".equals(jsonrpc)) {
      response.set("error", errorNode(A2aJsonRpcError.INVALID_REQUEST, "jsonrpc must be \"2.0\""));
      return Prepared.error(response);
    }
    String method = text(request, "method");
    if (!expectedMethod.equals(method)) {
      response.set(
          "error", errorNode(A2aJsonRpcError.METHOD_NOT_FOUND, "unsupported method: " + method));
      return Prepared.error(response);
    }
    JsonNode params = request.get("params");
    if (params == null || !params.isObject()) {
      response.set(
          "error", errorNode(A2aJsonRpcError.INVALID_PARAMS, "params.message is required"));
      return Prepared.error(response);
    }
    JsonNode message = params.get("message");
    if (message == null || !message.isObject()) {
      response.set(
          "error", errorNode(A2aJsonRpcError.INVALID_PARAMS, "params.message is required"));
      return Prepared.error(response);
    }
    String userText = extractText(message.get("parts"));
    if (userText.isBlank()) {
      response.set(
          "error", errorNode(A2aJsonRpcError.INVALID_PARAMS, "message.parts must include text"));
      return Prepared.error(response);
    }
    String agent = resolveAgent(params);
    if (agent.isBlank()) {
      response.set(
          "error",
          errorNode(
              A2aJsonRpcError.INVALID_PARAMS,
              "agent required: set params.metadata.agent or oryxos.a2a.default-agent"));
      return Prepared.error(response);
    }
    if (!agentKnown(agent)) {
      response.set("error", errorNode(A2aJsonRpcError.INVALID_PARAMS, "unknown agent: " + agent));
      return Prepared.error(response);
    }
    return Prepared.ok(response, agent, userText);
  }

  private String resolveAgent(JsonNode params) {
    JsonNode metadata = params.get("metadata");
    if (metadata != null && metadata.isObject()) {
      String fromMeta = text(metadata, "agent");
      if (!fromMeta.isBlank()) {
        return fromMeta.strip();
      }
      String skill = text(metadata, "skill");
      if (skill.startsWith("agent:") && skill.length() > "agent:".length()) {
        return skill.substring("agent:".length()).strip();
      }
    }
    return properties.defaultAgent();
  }

  private boolean agentKnown(String agent) {
    List<A2aAgentRef> refs = agents.get();
    if (refs == null || refs.isEmpty()) {
      return true;
    }
    for (A2aAgentRef ref : refs) {
      if (agent.equals(ref.name())) {
        return true;
      }
    }
    return false;
  }

  static String extractText(JsonNode parts) {
    if (parts == null || !parts.isArray()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (JsonNode part : parts) {
      if (part == null || !part.isObject()) {
        continue;
      }
      String kind = text(part, "kind");
      if (kind.isBlank()) {
        kind = text(part, "type");
      }
      if ("text".equals(kind) || part.has("text")) {
        String t = text(part, "text");
        if (!t.isBlank()) {
          if (sb.length() > 0) {
            sb.append('\n');
          }
          sb.append(t);
        }
      }
    }
    return sb.toString();
  }

  private static void copyId(JsonNode request, ObjectNode response) {
    JsonNode idNode = request.get("id");
    if (idNode == null || idNode.isNull()) {
      response.putNull("id");
    } else if (idNode.isIntegralNumber()) {
      response.put("id", idNode.longValue());
    } else if (idNode.isNumber()) {
      response.put("id", idNode.doubleValue());
    } else {
      response.put("id", idNode.asText());
    }
  }

  private static ObjectNode agentMessage(String text) {
    ObjectNode msg = MAPPER.createObjectNode();
    msg.put("kind", "message");
    msg.put("role", "agent");
    msg.put("messageId", UUID.randomUUID().toString());
    ArrayNode parts = msg.putArray("parts");
    ObjectNode part = parts.addObject();
    part.put("kind", "text");
    part.put("text", text);
    return msg;
  }

  private static ObjectNode errorNode(int code, String message) {
    ObjectNode err = MAPPER.createObjectNode();
    err.put("code", code);
    err.put("message", message);
    return err;
  }

  private static String text(JsonNode n, String field) {
    if (n == null) {
      return "";
    }
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? "" : v.asText("");
  }

  private record Prepared(ObjectNode response, ObjectNode error, String agent, String userText) {
    static Prepared error(ObjectNode response) {
      return new Prepared(response, response, null, null);
    }

    static Prepared ok(ObjectNode response, String agent, String userText) {
      return new Prepared(response, null, agent, userText);
    }
  }
}
