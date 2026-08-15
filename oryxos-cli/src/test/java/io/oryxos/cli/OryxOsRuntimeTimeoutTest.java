package io.oryxos.cli;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 工具 HTTP 超时回归：远端挂死时，{@code OryxOsRuntime.restClient()} 装配的客户端必须在读取超时内失败，
 * 不能无限阻塞 Agent 调用。
 *
 * <p>模式与 {@code ProviderChatModelFactoryTimeoutTest} 完全一致——挂死 HttpServer + CountDownLatch
 * 模拟「收到请求后永不响应」，通过系统属性把超时压到 1s 保证单测快速反馈。
 */
class OryxOsRuntimeTimeoutTest {

  private HttpServer hangingServer;
  private final CountDownLatch release = new CountDownLatch(1);

  @BeforeEach
  void startHangingServer() throws Exception {
    hangingServer = HttpServer.create(new InetSocketAddress(0), 0);
    hangingServer.createContext(
        "/",
        exchange -> {
          try {
            release.await(30, TimeUnit.SECONDS); // 收到请求后挂死，不响应不断连
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    hangingServer.start();
  }

  @AfterEach
  void stopServer() {
    release.countDown(); // 放行 handler，避免 stop(0) 卡住
    hangingServer.stop(0);
    System.clearProperty(OryxOsRuntime.TOOL_HTTP_CONNECT_TIMEOUT_PROP);
    System.clearProperty(OryxOsRuntime.TOOL_HTTP_READ_TIMEOUT_PROP);
  }

  @Test
  @DisplayName("工具 RestClient 远端挂死时在读取超时内失败，不永久阻塞")
  void toolRestClientFailsOnHangingEndpoint() {
    // 系统属性压到 1s——跟 ProviderChatModelFactoryTimeoutTest 完全同模式
    System.setProperty(OryxOsRuntime.TOOL_HTTP_CONNECT_TIMEOUT_PROP, "1");
    System.setProperty(OryxOsRuntime.TOOL_HTTP_READ_TIMEOUT_PROP, "1");

    RestClient client =
        RestClient.builder()
            .requestFactory(OryxOsRuntime.toolHttpRequestFactory())
            .build();

    String url = "http://127.0.0.1:" + hangingServer.getAddress().getPort() + "/api";

    // 修复前：默认 RestClient.create() 无超时，这里会永久阻塞（preemptive 10s 兜底防测试挂死）
    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () ->
            assertThrows(
                ResourceAccessException.class,
                () ->
                    client
                        .post()
                        .uri(url)
                        .body("{\"city\":\"beijing\"}")
                        .retrieve()
                        .toEntity(String.class)));
  }
}
