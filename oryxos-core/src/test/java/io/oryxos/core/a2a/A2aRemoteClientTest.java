package io.oryxos.core.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class A2aRemoteClientTest {

  private HttpServer server;
  private String origin;
  private final AtomicReference<String> lastBody = new AtomicReference<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int port = server.getAddress().getPort();
    origin = "http://127.0.0.1:" + port;
    server.createContext(
        "/.well-known/agent-card.json",
        ex -> {
          byte[] body =
              ("""
              {"protocolVersion":"0.3.0","name":"Peer","description":"t","url":"%s/api/v1/a2a",\
              "preferredTransport":"JSONRPC","version":"1","capabilities":{"streaming":false,\
              "pushNotifications":false,"stateTransitionHistory":false},\
              "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[]}\
              """
                      .formatted(origin))
                  .getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/api/v1/a2a",
        ex -> {
          lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body =
              """
              {"jsonrpc":"2.0","id":1,"result":{"kind":"message","role":"agent",\
              "messageId":"m2","parts":[{"kind":"text","text":"remote-pong"}]}}\
              """
                  .getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private A2aRemoteClient client() {
    return A2aRemoteClient.create(
        Duration.ofSeconds(5),
        uri -> {
          String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
          return "127.0.0.1".equals(host);
        });
  }

  @Test
  @DisplayName("fetchCard + message/send via card.url")
  void sendToBase_ok() throws Exception {
    String reply = client().sendToBase(URI.create(origin), "writer", "hello");
    assertEquals("remote-pong", reply);
    assertTrue(lastBody.get().contains("message/send"));
    assertTrue(lastBody.get().contains("writer"));
    assertTrue(lastBody.get().contains("hello"));
  }

  @Test
  @DisplayName("host not on allowlist rejected")
  void hostRejected() {
    A2aRemoteClient denied = A2aRemoteClient.create(Duration.ofSeconds(5), uri -> false);
    assertThrows(IllegalArgumentException.class, () -> denied.fetchCard(URI.create(origin)));
  }

  @Test
  @DisplayName("resolveCardUri appends well-known")
  void resolveCardUri() {
    assertEquals(
        URI.create("http://peer.example/.well-known/agent-card.json"),
        A2aRemoteClient.resolveCardUri(URI.create("http://peer.example")));
  }
}
