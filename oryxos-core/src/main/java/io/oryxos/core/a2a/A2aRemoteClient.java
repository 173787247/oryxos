package io.oryxos.core.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Outbound A2A client (cross-node): fetch Agent Card + JSON-RPC {@code message/send}. No redirects;
 * every request URI must pass the host allow predicate (SSRF gate). Optional Bearer from shared
 * token. Streaming / push out of scope.
 */
public final class A2aRemoteClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String WELL_KNOWN = "/.well-known/agent-card.json";
  private static final String BEARER_PREFIX = "Bearer ";

  private final HttpClient http;
  private final Duration timeout;
  private final Predicate<URI> hostAllowed;
  private final Supplier<String> bearerToken;

  public A2aRemoteClient(HttpClient http, Duration timeout, Predicate<URI> hostAllowed) {
    this(http, timeout, hostAllowed, () -> "");
  }

  public A2aRemoteClient(
      HttpClient http, Duration timeout, Predicate<URI> hostAllowed, Supplier<String> bearerToken) {
    this.http = Objects.requireNonNull(http, "http");
    this.timeout =
        timeout == null || timeout.isNegative() || timeout.isZero()
            ? Duration.ofSeconds(30)
            : timeout;
    this.hostAllowed = Objects.requireNonNull(hostAllowed, "hostAllowed");
    this.bearerToken = bearerToken == null ? () -> "" : bearerToken;
  }

  /** Build a no-redirect client with connect timeout = request timeout. */
  public static A2aRemoteClient create(Duration timeout, Predicate<URI> hostAllowed) {
    return create(timeout, hostAllowed, () -> "");
  }

  public static A2aRemoteClient create(
      Duration timeout, Predicate<URI> hostAllowed, Supplier<String> bearerToken) {
    Duration t =
        timeout == null || timeout.isZero() || timeout.isNegative()
            ? Duration.ofSeconds(30)
            : timeout;
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(t)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    return new A2aRemoteClient(http, t, hostAllowed, bearerToken);
  }

  /** GET Agent Card from {@code baseUrl} (origin or full card URL). */
  public A2aAgentCard fetchCard(URI baseUrl) throws IOException, InterruptedException {
    URI cardUri = resolveCardUri(baseUrl);
    requireAllowed(cardUri);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(cardUri)
            .timeout(timeout)
            .GET()
            .header("Accept", "application/json");
    applyAuth(builder);
    HttpResponse<String> response =
        http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    int code = response.statusCode();
    if (code < 200 || code >= 300) {
      throw new IOException("A2A card HTTP " + code + " for " + sanitize(cardUri));
    }
    try {
      return MAPPER.readValue(response.body(), A2aAgentCard.class);
    } catch (IOException e) {
      throw new IOException("invalid A2A Agent Card JSON from " + sanitize(cardUri), e);
    }
  }

  /**
   * POST {@code message/send} to the peer's A2A JSON-RPC URL. Returns agent reply text (joined text
   * parts) or empty string.
   */
  public String messageSend(URI a2aUrl, String agent, String text)
      throws IOException, InterruptedException {
    Objects.requireNonNull(a2aUrl, "a2aUrl");
    if (agent == null || agent.isBlank()) {
      throw new IllegalArgumentException("agent must not be blank");
    }
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("text must not be blank");
    }
    requireAllowed(a2aUrl);
    ObjectNode req = MAPPER.createObjectNode();
    req.put("jsonrpc", "2.0");
    req.put("id", 1);
    req.put("method", A2aMessageService.METHOD_MESSAGE_SEND);
    ObjectNode params = req.putObject("params");
    ObjectNode message = params.putObject("message");
    message.put("kind", "message");
    message.put("role", "user");
    message.put("messageId", UUID.randomUUID().toString());
    ObjectNode part = message.putArray("parts").addObject();
    part.put("kind", "text");
    part.put("text", text.strip());
    params.putObject("metadata").put("agent", agent.strip());

    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(a2aUrl)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(req)));
    applyAuth(builder);
    HttpResponse<String> response =
        http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    int code = response.statusCode();
    if (code < 200 || code >= 300) {
      throw new IOException("A2A message/send HTTP " + code + " for " + sanitize(a2aUrl));
    }
    JsonNode body = MAPPER.readTree(response.body());
    if (body.has("error") && !body.get("error").isNull()) {
      JsonNode err = body.get("error");
      String msg = err.path("message").asText("A2A error");
      int rpcCode = err.path("code").asInt(0);
      throw new IOException("A2A JSON-RPC " + rpcCode + ": " + msg);
    }
    return extractReplyText(body.path("result"));
  }

  /**
   * Discover card from {@code baseUrl}, then {@code message/send} to {@code card.url()} with {@code
   * agent} / {@code text}.
   */
  public String sendToBase(URI baseUrl, String agent, String text)
      throws IOException, InterruptedException {
    A2aAgentCard card = fetchCard(baseUrl);
    if (card.url() == null || card.url().isBlank()) {
      throw new IOException("Agent Card missing url");
    }
    URI a2a = URI.create(card.url().strip());
    return messageSend(a2a, agent, text);
  }

  public static URI resolveCardUri(URI baseUrl) {
    Objects.requireNonNull(baseUrl, "baseUrl");
    String path = baseUrl.getPath() == null ? "" : baseUrl.getPath();
    if (path.endsWith("agent-card.json") || path.contains("/.well-known/")) {
      return baseUrl;
    }
    String origin = baseUrl.getScheme() + "://" + baseUrl.getRawAuthority();
    return URI.create(origin + WELL_KNOWN);
  }

  static String extractReplyText(JsonNode result) {
    if (result == null || result.isMissingNode() || result.isNull()) {
      return "";
    }
    JsonNode parts = result.get("parts");
    if (parts == null || !parts.isArray()) {
      return result.path("text").asText("");
    }
    StringBuilder sb = new StringBuilder();
    for (JsonNode part : parts) {
      if (part != null && part.has("text")) {
        String t = part.path("text").asText("");
        if (!t.isBlank()) {
          if (sb.length() > 0) {
            sb.append('\n');
          }
          sb.append(t);
        }
      }
    }
    return sb.toString();
  }

  private void applyAuth(HttpRequest.Builder builder) {
    String token = bearerToken.get();
    if (token != null && !token.isBlank()) {
      builder.header("Authorization", BEARER_PREFIX + token.strip());
    }
  }

  private void requireAllowed(URI uri) {
    if (!hostAllowed.test(uri)) {
      String host = uri.getHost() == null ? "" : uri.getHost();
      throw new IllegalArgumentException(
          "A2A remote host not allowed: "
              + host.toLowerCase(Locale.ROOT)
              + " (set oryxos.a2a.remote-hosts)");
    }
  }

  private static String sanitize(URI uri) {
    if (uri == null) {
      return "";
    }
    String host = uri.getHost() == null ? "" : uri.getHost();
    return uri.getScheme() + "://" + host + (uri.getPath() == null ? "" : uri.getPath());
  }
}
