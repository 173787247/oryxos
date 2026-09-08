package io.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundChannelRegistry;
import io.oryxos.core.channel.InboundWebhookHandler;
import io.oryxos.core.channel.WebhookRequest;
import io.oryxos.core.channel.WebhookResponse;
import io.oryxos.web.error.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 入站 IM 共享 Webhook 面（026 P0）：按渠道名查找运行中适配器；仅 {@link InboundWebhookHandler} 受理，否则 404。
 *
 * <p>响应体按适配器原样回写（挑战握手 / 验签回执），不套 {@code ApiResponse} 信封。
 */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "core-stage web API is unauthenticated by design; registry 是 Runtime 装配的共享单例。")
@RestController
@RequestMapping("/api/v1/channels/inbound")
public class ChannelInboundWebhookController {

  private final InboundChannelRegistry registry;

  public ChannelInboundWebhookController(InboundChannelRegistry registry) {
    this.registry = registry;
  }

  @RequestMapping(
      path = "/{name}",
      method = {RequestMethod.GET, RequestMethod.POST})
  public ResponseEntity<String> inbound(
      @PathVariable String name,
      HttpServletRequest request,
      @RequestBody(required = false) byte[] rawBody) {
    InboundChannelAdapter adapter =
        registry
            .get(name)
            .orElseThrow(() -> new ResourceNotFoundException("入站 webhook 渠道不存在或未上线: " + name));
    if (!(adapter instanceof InboundWebhookHandler handler)) {
      throw new ResourceNotFoundException("渠道 " + name + " 不支持入站 webhook");
    }
    WebhookRequest webhookRequest =
        new WebhookRequest(
            request.getMethod(), queryMap(request), headerMap(request), bodyText(rawBody));
    WebhookResponse response = handler.onWebhook(webhookRequest);
    MediaType mediaType = MediaType.parseMediaType(response.contentType());
    return ResponseEntity.status(response.status()).contentType(mediaType).body(response.body());
  }

  private static String bodyText(byte[] rawBody) {
    if (rawBody == null || rawBody.length == 0) {
      return "";
    }
    return new String(rawBody, StandardCharsets.UTF_8);
  }

  private static Map<String, String> queryMap(HttpServletRequest request) {
    Map<String, String> query = new LinkedHashMap<>();
    request
        .getParameterMap()
        .forEach(
            (key, values) -> {
              if (values != null && values.length > 0 && values[0] != null) {
                query.put(key, values[0]);
              }
            });
    return query;
  }

  private static Map<String, String> headerMap(HttpServletRequest request) {
    Map<String, String> headers = new LinkedHashMap<>();
    Enumeration<String> names = request.getHeaderNames();
    if (names == null) {
      return headers;
    }
    while (names.hasMoreElements()) {
      String name = names.nextElement();
      if (name != null) {
        headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
      }
    }
    return headers;
  }
}
