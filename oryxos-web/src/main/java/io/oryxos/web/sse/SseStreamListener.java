package io.oryxos.web.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.StreamListener;
import java.util.Map;

/**
 * {@link StreamListener} → {@link SseWriter} 适配（019）：核心回调翻译成 SSE 业务事件 （负载 JSON 契约见
 * contracts/sse-protocol.md §2）。done/error 终结事件由 controller 掌握，不在此发。
 */
public final class SseStreamListener implements StreamListener {

  private final SseWriter writer;
  private final ObjectMapper objectMapper;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "writer 是本次请求专属的写出器、objectMapper 是 Spring 共享单例，存引用正是意图。")
  public SseStreamListener(SseWriter writer, ObjectMapper objectMapper) {
    this.writer = writer;
    this.objectMapper = objectMapper;
  }

  @Override
  public void onToken(String delta) {
    writer.event("token", json(Map.of("delta", delta)));
  }

  @Override
  public void onToolStart(String toolName) {
    writer.event("tool_start", json(Map.of("name", toolName)));
  }

  @Override
  public void onToolEnd(String toolName, boolean success) {
    writer.event("tool_end", json(Map.of("name", toolName, "success", success)));
  }

  private String json(Map<String, ?> payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      // Map<String,String/Boolean> 序列化不可能失败；防御性兜底
      throw new IllegalStateException("SSE payload serialization failed", e);
    }
  }
}
