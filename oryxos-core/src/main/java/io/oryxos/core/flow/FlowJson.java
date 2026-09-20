package io.oryxos.core.flow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSON helpers for Flow run context / step IO (046 / #468). */
final class FlowJson {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private FlowJson() {}

  static String write(Map<String, Object> map) {
    try {
      return MAPPER.writeValueAsString(map == null ? Map.of() : map);
    } catch (Exception e) {
      throw new IllegalStateException("Flow JSON serialize failed", e);
    }
  }

  static Map<String, Object> readMap(String json) {
    if (json == null || json.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      Map<String, Object> map = MAPPER.readValue(json, MAP_TYPE);
      return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
    } catch (Exception e) {
      throw new IllegalStateException("Flow JSON parse failed", e);
    }
  }
}
