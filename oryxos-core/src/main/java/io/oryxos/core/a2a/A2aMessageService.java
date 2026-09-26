package io.oryxos.core.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Handles A2A JSON-RPC {@code message/send}: extract text parts, route to a local agent via {@code
 * processStateless}, return an agent Message result. Streaming / tasks / push are out of scope.
 */
public final class A2aMessageService {

  public static final String METHOD_MESSAGE_SEND = "message/send";

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
    ObjectNode response = MAPPER.createObjectNode();
    response.put("jsonrpc", "2.0");
    if (request == null || !request.isObject()) {
      response.putNull("id");
      response.set(
          "error", errorNode(A2aJsonRpcError.INVALID_REQUEST, "request must be a JSON object"));
      return response;
    }
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
    String jsonrpc = text(request, "jsonrpc");
    if (!"2.0".equals(jsonrpc)) {
      response.set("error", errorNode(A2aJsonRpcError.INVALID_REQUEST, "jsonrpc must be \"2.0\""));
      return response;
    }
    String method = text(request, "method");
    if (!METHOD_MESSAGE_SEND.equals(method)) {
      response.set(
          "error", errorNode(A2aJsonRpcError.METHOD_NOT_FOUND, "unsupported method: " + method));
      return response;
    }
    try {
      JsonNode params = request.get("params");
      if (params == null || !params.isObject()) {
        response.set(
            "error", errorNode(A2aJsonRpcError.INVALID_PARAMS, "params.message is required"));
        return response;
      }
      JsonNode message = params.get("message");
      if (message == null || !message.isObject()) {
        response.set(
            "error", errorNode(A2aJsonRpcError.INVALID_PARAMS, "params.message is required"));
        return response;
      }
      String userText = extractText(message.get("parts"));
      if (userText.isBlank()) {
        response.set(
            "error", errorNode(A2aJsonRpcError.INVALID_PARAMS, "message.parts must include text"));
        return response;
      }
      String agent = resolveAgent(params);
      if (agent.isBlank()) {
        response.set(
            "error",
            errorNode(
                A2aJsonRpcError.INVALID_PARAMS,
                "agent required: set params.metadata.agent or oryxos.a2a.default-agent"));
        return response;
      }
      if (!agentKnown(agent)) {
        response.set("error", errorNode(A2aJsonRpcError.INVALID_PARAMS, "unknown agent: " + agent));
        return response;
      }
      String reply = agentRunner.apply(agent, userText);
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
}
