package io.oryxos.web.sse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.StreamListener;
import io.oryxos.core.agent.TraceContext;
import io.oryxos.web.config.WebSseProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * SSE 流式编排（019）：三个消息端点共用的「建 writer → 跑 ReAct → done/error 终结 → close」骨架， 保证终结事件恰好一个（FR-003）且
 * controller 不重复样板。
 *
 * <p>调用方职责：进入本方法前完成<b>全部</b>前置校验（会话/Agent 存在、消息合法）——校验失败走既有异常体系 返 JSON
 * 状态码（FR-009）；一旦进入本方法即视为流已开始，之后的失败只能以 error 事件表达。
 */
@Component
public class SseStreamSupport {

  private final ObjectMapper objectMapper;
  private final WebSseProperties properties;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "objectMapper/properties 均为 Spring 注入的共享单例，构造注入存同一引用正是意图。")
  public SseStreamSupport(ObjectMapper objectMapper, WebSseProperties properties) {
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  /** 无 DI 场景的默认实例（controller 望远镜构造器/测试直构用；运行时由 @Autowired setter 覆盖为容器单例）。 */
  public static SseStreamSupport defaultSupport() {
    return new SseStreamSupport(new ObjectMapper(), new WebSseProperties());
  }

  /** 请求是否声明接受事件流（FR-001 分流判定）。 */
  public static boolean wantsEventStream(HttpServletRequest request) {
    String accept = request.getHeader("Accept");
    return accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
  }

  /**
   * 执行一次流式处理：{@code action} 拿着 listener 跑完整 ReAct 并返回最终回复。正常返回 → {@code done} 事件（{"reply":…}）；抛异常 →
   * {@code error} 事件（可读信息，不泄堆栈）。终结后关闭连接。 客户端断开由 {@link SseWriter} 静默吸收，{@code action} 照常完成（FR-008）。
   */
  public void stream(HttpServletResponse response, Function<StreamListener, String> action) {
    try (SseWriter writer = new SseWriter(response, properties.getHeartbeatSeconds())) {
      // 021：流建立即回传本轮 trace ID（controller 已 open）——新事件类型只增不改，旧客户端忽略
      String traceId = TraceContext.current();
      if (traceId != null) {
        writer.event("trace", json(Map.of("traceId", traceId)));
      }
      StreamListener listener = new SseStreamListener(writer, objectMapper);
      try {
        String reply = action.apply(listener);
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("reply", reply == null ? "" : reply);
        if (traceId != null) {
          done.put("traceId", traceId); // done 同带（021）：断流场景客户端仍可从流首 trace 事件拿到
        }
        writer.event("done", json(done));
      } catch (RuntimeException e) {
        // 流已开始（HTTP 已 200），失败只能以 error 事件表达（FR-009 分层）；信息可读、不带堆栈
        writer.event("error", json(Map.of("code", 500, "message", safeMessage(e))));
      }
    } catch (IOException e) {
      // 响应输出流都拿不到：流从未建立，交给容器/全局兜底
      throw new UncheckedIOException("failed to open SSE response stream", e);
    }
  }

  private String json(Map<String, ?> payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("SSE payload serialization failed", e);
    }
  }

  private static String safeMessage(RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }
}
