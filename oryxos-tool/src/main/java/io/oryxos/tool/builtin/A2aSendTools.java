package io.oryxos.tool.builtin;

import io.oryxos.core.a2a.A2aRemoteClient;
import java.net.URI;
import java.util.Objects;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Cross-node A2A outbound: discover Agent Card then {@code message/send}. Requires {@code
 * oryxos.a2a.enabled} + host allowlist.
 */
public class A2aSendTools {

  private static final int MAX_REPLY_CHARS = 100_000;

  private final A2aRemoteClient client;
  private final boolean enabled;

  public A2aSendTools(A2aRemoteClient client, boolean enabled) {
    this.client = Objects.requireNonNull(client, "client");
    this.enabled = enabled;
  }

  @Tool(
      name = "a2a_send",
      description =
          "Send a message to a remote OryxOS / A2A peer: GET Agent Card from baseUrl, then "
              + "JSON-RPC message/send to card.url. Host must be on oryxos.a2a.remote-hosts.")
  public String a2aSend(
      @ToolParam(description = "Peer base URL, e.g. http://host:8080") String baseUrl,
      @ToolParam(description = "Remote agent / skill name (metadata.agent)") String agent,
      @ToolParam(description = "User text for the remote agent") String message) {
    if (!enabled) {
      throw new IllegalStateException("a2a_send is disabled; set oryxos.a2a.enabled=true");
    }
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl must not be blank");
    }
    if (agent == null || agent.isBlank()) {
      throw new IllegalArgumentException("agent must not be blank");
    }
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("message must not be blank");
    }
    try {
      String reply = client.sendToBase(URI.create(baseUrl.strip()), agent.strip(), message.strip());
      if (reply == null) {
        return "";
      }
      if (reply.length() > MAX_REPLY_CHARS) {
        return reply.substring(0, MAX_REPLY_CHARS) + "\n...[a2a_send truncated]";
      }
      return reply;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("a2a_send interrupted", e);
    } catch (Exception e) {
      String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      throw new IllegalStateException("a2a_send failed: " + msg, e);
    }
  }
}
