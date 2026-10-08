package io.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 手工构造的 SDK 客户端（{@link ProviderChatModelFactory#sdkClient}）不得丢掉 Spring AI 那套装配本来会做的事：带
 * 版本段的路径、Bearer 凭证。超时改造只该换「超时从哪来」，不该换线上长相。
 */
class ProviderChatModelFactorySdkClientTest {

  private final AtomicReference<String> requestedPath = new AtomicReference<>();

  private final AtomicReference<String> requestedAuthorization = new AtomicReference<>();

  private HttpServer completionServer;

  @BeforeEach
  void startCompletionServer() throws Exception {
    completionServer = HttpServer.create(new InetSocketAddress(0), 0);
    completionServer.createContext("/", this::answerWithPong);
    completionServer.start();
  }

  @AfterEach
  void stopCompletionServer() {
    completionServer.stop(0);
  }

  @Test
  @DisplayName("请求打到 /v1/chat/completions 且带 Bearer 凭证_自定义 HttpClient 不改线上契约")
  void sdkClientKeepsVersionedPathAndBearerCredential() {
    // 配置里的 baseUrl 照约定不含 /v1（ProviderChatModelFactory.normalizeOpenAiBaseUrl 补）
    String baseUrl = "http://127.0.0.1:" + completionServer.getAddress().getPort();
    ChatModel model = new ProviderChatModelFactory().buildOne("p1", "sk-test", baseUrl);

    ChatResponse response = model.call(new Prompt("ping"));

    assertThat(response.getResult().getOutput().getText()).isEqualTo("pong");
    assertThat(requestedPath.get()).isEqualTo("/v1/chat/completions");
    assertThat(requestedAuthorization.get()).isEqualTo("Bearer sk-test");
  }

  private void answerWithPong(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
    requestedPath.set(exchange.getRequestURI().getPath());
    requestedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    byte[] body =
        ("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"pong\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
