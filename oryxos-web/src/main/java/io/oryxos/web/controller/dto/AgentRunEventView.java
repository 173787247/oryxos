package io.oryxos.web.controller.dto;

import io.oryxos.core.agent.AgentRunEvent;
import java.time.Instant;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Run Event 对外视图。
 *
 * <p>payload 是出网序列化器（Jackson 3，{@code tools.jackson}）的树：Boot 4 / Spring 7 的 MVC JSON 转换器只认这个 类型，喂给它
 * Jackson 2（{@code com.fasterxml}）的 JsonNode 时不报错，而是按 POJO 序列化成 {@code
 * {"array":false,"nodeType":"OBJECT",…}} 属性转储，真正的载荷内容全丢。core 的 payload 仍是 JSON 字符串，所以在
 * 这一层边界用同一个（Jackson 3）映射器解析，出网路径上没有 Jackson 2 的树。
 */
public record AgentRunEventView(
    int schemaVersion,
    long runId,
    long sequence,
    String type,
    Instant createdAt,
    JsonNode payload) {

  private static final String SCHEMA_VERSION_FIELD = "schemaVersion";
  private static final JsonMapper WIRE_MAPPER = JsonMapper.builder().build();

  public static AgentRunEventView from(AgentRunEvent event) {
    JsonNode payload = parsePayload(event.payloadJson());
    int version =
        payload.has(SCHEMA_VERSION_FIELD) ? payload.get(SCHEMA_VERSION_FIELD).asInt(1) : 1;
    return new AgentRunEventView(
        version, event.runId(), event.sequence(), event.type(), event.createdAt(), payload);
  }

  private static JsonNode parsePayload(String json) {
    try {
      return WIRE_MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
    } catch (JacksonException e) {
      return WIRE_MAPPER.createObjectNode();
    }
  }
}
