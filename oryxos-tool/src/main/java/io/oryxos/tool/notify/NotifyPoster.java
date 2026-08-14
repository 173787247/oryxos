package io.oryxos.tool.notify;

import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 通知推送共用 HTTP 出口：禁自动重定向，手动逐跳跟随并对<strong>每一跳</strong>过 {@code HTTP_REQUEST} 域名白名单——杜绝「白名单内 webhook
 * 入口 302 到白名单外 / 内网」的绕过（与 {@code HttpTools} 写路径同策略）。
 */
public final class NotifyPoster {

  private static final int MAX_REDIRECTS = 5;
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

  private final Sandbox sandbox;
  private final RestClient hopClient;

  public NotifyPoster(Sandbox sandbox) {
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox 不能为空");
    JdkClientHttpRequestFactory hopFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    hopFactory.setReadTimeout(READ_TIMEOUT);
    this.hopClient = RestClient.builder().requestFactory(hopFactory).build();
  }

  /** POST JSON body 到 url；3xx 时跟随 Location，每跳先过沙箱。 */
  public void postJson(String url, Object body) {
    String current = url;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, current));
      ResponseEntity<Void> resp =
          hopClient
              .post()
              .uri(current)
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .toBodilessEntity();
      if (resp.getStatusCode().is3xxRedirection()) {
        String location = resp.getHeaders().getFirst("Location");
        if (location == null || location.isBlank()) {
          return;
        }
        current = URI.create(current).resolve(location).toString();
        continue;
      }
      return;
    }
    throw new IllegalStateException("通知推送重定向次数过多，拒绝: " + url);
  }
}
