package io.oryxos.web.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** SseStreamListener 单元：核心回调 → SSE 业务事件的负载契约（contracts/sse-protocol.md §2）。 */
class SseStreamListenerTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final SseWriter writer = mock(SseWriter.class);
  private final SseStreamListener listener = new SseStreamListener(writer, mapper);

  @Test
  @DisplayName("token 回调翻译为 token 事件")
  void mapsTokenDelta() throws Exception {
    listener.onToken("Hel");

    JsonNode payload = capturePayload("token");
    assertEquals("Hel", payload.path("delta").asText());
  }

  @Test
  @DisplayName("token 里的引号/换行/反斜杠被 JSON 转义，客户端还原后不变")
  void escapesTokenDeltaAsJson() throws Exception {
    String delta = "a\"b\\c\nd\te";

    listener.onToken(delta);

    assertEquals(delta, capturePayload("token").path("delta").asText());
  }

  @Test
  @DisplayName("tool_start 回调带工具名")
  void mapsToolStart() throws Exception {
    listener.onToolStart("read_file");

    assertEquals("read_file", capturePayload("tool_start").path("name").asText());
  }

  @Test
  @DisplayName("tool_end 回调带工具名与成功标记")
  void mapsToolEnd() throws Exception {
    listener.onToolEnd("write_file", false);

    JsonNode payload = capturePayload("tool_end");
    assertEquals("write_file", payload.path("name").asText());
    assertEquals(false, payload.path("success").asBoolean());
  }

  private JsonNode capturePayload(String expectedType) throws Exception {
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(writer).event(eq(expectedType), payload.capture());
    return mapper.readTree(payload.getValue());
  }
}
