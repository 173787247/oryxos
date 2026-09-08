package io.oryxos.channel.gchat;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.util.List;
import java.util.Optional;

/** Google Chat HTTP 事件 {@code MESSAGE} → {@link InboundMessage}。空间仅当带 argumentText / 注解。 */
public class GChatEventNormalizer {

  static final String CHANNEL_TYPE = "gchat";

  private final String channelName;

  public GChatEventNormalizer(String channelName) {
    this.channelName = channelName;
  }

  public Optional<InboundMessage> normalize(JsonNode root) {
    if (root == null || !root.isObject()) {
      return Optional.empty();
    }
    if (!"MESSAGE".equals(root.path("type").asText(""))) {
      return Optional.empty();
    }
    JsonNode message = root.path("message");
    String messageId = text(message, "name");
    String userId = text(message.path("sender"), "name");
    String chatId = text(message.path("space"), "name");
    if (messageId == null || userId == null || chatId == null) {
      return Optional.empty();
    }
    String spaceType = message.path("space").path("type").asText("");
    boolean dm = "DM".equals(spaceType);
    String argument = message.path("argumentText").asText("").strip();
    String text = argument.isBlank() ? message.path("text").asText("").strip() : argument;
    if (!dm) {
      boolean mentioned =
          !argument.isBlank()
              || message.path("annotations").isArray() && message.path("annotations").size() > 0;
      if (!mentioned) {
        return Optional.empty();
      }
      if (text.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(
          new InboundMessage(
              CHANNEL_TYPE,
              channelName,
              messageId,
              ChatKind.GROUP,
              userId,
              chatId,
              text,
              true,
              true,
              List.of()));
    }
    if (text.isBlank()) {
      return Optional.of(
          new InboundMessage(
              CHANNEL_TYPE,
              channelName,
              messageId,
              ChatKind.P2P,
              userId,
              chatId,
              "",
              false,
              false,
              List.of()));
    }
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            messageId,
            ChatKind.P2P,
            userId,
            chatId,
            text,
            true,
            false,
            List.of()));
  }

  private static String text(JsonNode node, String field) {
    if (node == null || !node.isObject()) {
      return null;
    }
    JsonNode v = node.get(field);
    if (v == null || v.isNull()) {
      return null;
    }
    String s = v.asText();
    return s == null || s.isBlank() ? null : s;
  }
}
