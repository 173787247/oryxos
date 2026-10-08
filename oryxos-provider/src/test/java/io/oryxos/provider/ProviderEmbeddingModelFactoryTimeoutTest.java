package io.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.embedding.TextEmbedder;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** embedding 侧走的是同一套超时改造（{@link ProviderChatModelFactory#sdkClient}）：挂死端点必须有界失败，且线上契约（路径、凭证）不许变。 */
class ProviderEmbeddingModelFactoryTimeoutTest {

  private HttpServer server;

  private final CountDownLatch release = new CountDownLatch(1);

  private final AtomicReference<String> requestedPath = new AtomicReference<>();

  private final AtomicReference<String> requestedAuthorization = new AtomicReference<>();

  private final AtomicReference<Boolean> hangRequests = new AtomicReference<>(false);

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  @AfterEach
  void stopServer() {
    release.countDown();
    server.stop(0);
    System.clearProperty(ProviderChatModelFactory.READ_TIMEOUT_PROP);
  }

  @Test
  @DisplayName("embedding 端点挂死_在读取超时内失败而非永久阻塞")
  void hangingEmbeddingEndpointFailsWithinReadTimeout() {
    System.setProperty(ProviderChatModelFactory.READ_TIMEOUT_PROP, "1");
    TextEmbedder embedder =
        embedderFor("hang", "http://127.0.0.1:" + server.getAddress().getPort());

    hangRequests.set(true);
    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () ->
            assertThatThrownBy(() -> embedder.embed("ping")).isInstanceOf(RuntimeException.class));
  }

  @Test
  @DisplayName("embedding 请求打到 /v1/embeddings 且带 Bearer 凭证")
  void embeddingKeepsVersionedPathAndBearerCredential() {
    TextEmbedder embedder =
        embedderFor("qwen", "http://127.0.0.1:" + server.getAddress().getPort());

    float[] vector = embedder.embed("ping");

    assertThat(vector).containsExactly(0.5f, 0.25f);
    assertThat(requestedPath.get()).isEqualTo("/v1/embeddings");
    assertThat(requestedAuthorization.get()).isEqualTo("Bearer sk-test");
  }

  private TextEmbedder embedderFor(String name, String baseUrl) {
    return new ProviderEmbeddingModelFactory()
        .buildOne(name, "sk-test", baseUrl, "text-embedding-v4");
  }

  private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
    requestedPath.set(exchange.getRequestURI().getPath());
    requestedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    if (Boolean.TRUE.equals(hangRequests.get())) {
      try {
        release.await(30, TimeUnit.SECONDS); // 收到请求后既不响应也不断连
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      exchange.close();
      return;
    }
    byte[] body =
        ("{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,"
                + "\"embedding\":[0.5,0.25]}],\"model\":\"text-embedding-v4\","
                + "\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}")
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
