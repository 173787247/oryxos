package io.oryxos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.ToolResult;
import io.oryxos.tool.ToolRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the real MCP SDK transports and JSON serialization against local protocol fixtures. */
class McpTransportCompatibilityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path dir;

  @Test
  @Timeout(15)
  void stdioInitializesListsAndCallsThroughRealTransport() throws Exception {
    Path server = writeStdioServer();
    McpClientService service = serviceFor("stdio", server.toString(), null);
    ToolRegistry registry = new ToolRegistry();

    service.connectAll(registry);

    assertRoundTrip(registry, "stdio");
    service.disconnect("fixture", registry);
  }

  @Test
  @Timeout(15)
  void httpSseInitializesListsAndCallsThroughRealTransport() throws Exception {
    try (SseFixture server = new SseFixture()) {
      McpClientService service = serviceFor("http", null, server.baseUrl());
      ToolRegistry registry = new ToolRegistry();

      service.connectAll(registry);

      assertRoundTrip(registry, "http-sse");
      service.disconnect("fixture", registry);
    }
  }

  private McpClientService serviceFor(String transport, String command, String url)
      throws IOException {
    Path config = dir.resolve(transport + ".yaml");
    Files.writeString(
        config,
        "servers:\n"
            + "  - name: fixture\n"
            + "    transport: "
            + transport
            + "\n"
            + (command == null ? "" : "    command: " + command + "\n")
            + (url == null ? "" : "    url: " + url + "\n")
            + "    request_timeout: 5\n");
    return new McpClientService(new McpConfigLoader(config));
  }

  private static void assertRoundTrip(ToolRegistry registry, String transport) {
    assertTrue(registry.contains("echo"));
    ToolResult result =
        registry
            .get("echo")
            .orElseThrow()
            .execute(MAPPER.createObjectNode().put("value", transport));
    assertTrue(result.success());
    assertEquals("reply:" + transport, result.content());
  }

  private Path writeStdioServer() throws IOException {
    Path script = dir.resolve("mcp-fixture.py");
    Files.writeString(
        script,
        """
        #!/usr/bin/env python3
        import json, sys

        for line in sys.stdin:
            request = json.loads(line)
            request_id = request.get("id")
            if request_id is None:
                continue
            method = request.get("method")
            if method == "initialize":
                result = {"protocolVersion": "2024-11-05", "capabilities": {"tools": {}},
                          "serverInfo": {"name": "stdio-fixture", "version": "1"}}
            elif method == "tools/list":
                result = {"tools": [{"name": "echo", "description": "echo",
                                      "inputSchema": {"type": "object"}}]}
            elif method == "tools/call":
                value = request["params"]["arguments"]["value"]
                result = {"content": [{"type": "text", "text": "reply:" + value}],
                          "isError": False}
            else:
                result = {}
            print(json.dumps({"jsonrpc": "2.0", "id": request_id, "result": result}), flush=True)
        """);
    try {
      Files.setPosixFilePermissions(
          script,
          EnumSet.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE));
    } catch (UnsupportedOperationException e) {
      assertTrue(script.toFile().setExecutable(true), "fixture must be executable");
    }
    return script;
  }

  private static final class SseFixture implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile OutputStream events;

    private SseFixture() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/sse", this::openEvents);
      server.createContext("/messages", this::receiveMessage);
      server.setExecutor(executor);
      server.start();
    }

    private String baseUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void openEvents(HttpExchange exchange) throws IOException {
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream output = exchange.getResponseBody()) {
        events = output;
        sendEvent("endpoint", "/messages");
        try {
          closed.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      } finally {
        exchange.close();
      }
    }

    private void receiveMessage(HttpExchange exchange) throws IOException {
      JsonNode request = MAPPER.readTree(exchange.getRequestBody());
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      if (!request.has("id")) {
        return;
      }
      String method = request.path("method").asText();
      JsonNode result;
      if ("initialize".equals(method)) {
        result =
            MAPPER.readTree(
                """
                {"protocolVersion":"2024-11-05","capabilities":{"tools":{}},
                 "serverInfo":{"name":"sse-fixture","version":"1"}}
                """);
      } else if ("tools/list".equals(method)) {
        result =
            MAPPER.readTree(
                """
                {"tools":[{"name":"echo","description":"echo",
                            "inputSchema":{"type":"object"}}]}
                """);
      } else if ("tools/call".equals(method)) {
        String value = request.path("params").path("arguments").path("value").asText();
        result =
            MAPPER
                .createObjectNode()
                .set(
                    "content",
                    MAPPER
                        .createArrayNode()
                        .add(
                            MAPPER
                                .createObjectNode()
                                .put("type", "text")
                                .put("text", "reply:" + value)));
      } else {
        result = MAPPER.createObjectNode();
      }
      var response = MAPPER.createObjectNode();
      response.put("jsonrpc", "2.0");
      response.set("id", request.get("id"));
      response.set("result", result);
      sendEvent("message", MAPPER.writeValueAsString(response));
    }

    private synchronized void sendEvent(String type, String data) throws IOException {
      OutputStream output = events;
      if (output == null) {
        throw new IOException("SSE stream is not connected");
      }
      output.write(
          ("event: " + type + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
      output.flush();
    }

    @Override
    public void close() throws Exception {
      closed.countDown();
      server.stop(0);
      executor.shutdownNow();
      assertTrue(
          executor.awaitTermination(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS));
    }
  }
}
