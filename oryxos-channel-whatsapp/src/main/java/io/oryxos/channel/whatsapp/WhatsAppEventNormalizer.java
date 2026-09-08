package io.oryxos.channel.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** WhatsApp Cloud API webhook {@code entry[].changes[].value.messages[]} → 业务会话按 P2P 处理。 */
public class WhatsAppEventNormalizer {

  static final String CHANNEL_TYPE = "whatsapp";

  private final String channelName;

  public WhatsAppEventNormalizer(String channelName) {
    this.channelName = channelName;
  }

  public List<InboundMessage> normalize(JsonNode root) {
    List<InboundMessage> out = new ArrayList<>();
    if (root == null || !root.isObject()) {
      return out;
    }
    JsonNode entries = root.path("entry");
    if (!entries.isArray()) {
      return out;
    }
    for (JsonNode entry : entries) {
      JsonNode changes = entry.path("changes");
      if (!changes.isArray()) {
        continue;
      }
      for (JsonNode change : changes) {
        JsonNode value = change.path("value");
        JsonNode messages = value.path("messages");
        if (!messages.isArray()) {
          continue;
        }
        for (JsonNode message : messages) {
          normalizeOne(message).ifPresent(out::add);
        }
      }
    }
    return out;
  }

  private Optional<InboundMessage> normalizeOne(JsonNode message) {
    String messageId = text(message, "id");
    String from = text(message, "from");
    if (messageId == null || from == null) {
      return Optional.empty();
    }
    String type = message.path("type").asText("text");
    String content = "";
    List<InboundAttachment> attachments = new ArrayList<>();
    boolean textual = false;
    if ("text".equals(type)) {
      content = message.path("text").path("body").asText("").strip();
      textual = !content.isBlank();
    } else if ("image".equals(type)) {
      String id = text(message.path("image"), "id");
      if (id != null) {
        attachments.add(InboundAttachment.imageReference(id));
      }
    } else if ("audio".equals(type)) {
      String id = text(message.path("audio"), "id");
      if (id != null) {
        attachments.add(InboundAttachment.audioReference(id));
      }
    } else if ("video".equals(type)) {
      String id = text(message.path("video"), "id");
      if (id != null) {
        attachments.add(InboundAttachment.videoReference(id));
      }
    } else if ("document".equals(type)) {
      String id = text(message.path("document"), "id");
      if (id != null) {
        attachments.add(
            InboundAttachment.fileReference(id, text(message.path("document"), "filename")));
      }
    }
    if (!textual && attachments.isEmpty()) {
      return Optional.of(
          new InboundMessage(
              CHANNEL_TYPE,
              channelName,
              messageId,
              ChatKind.P2P,
              from,
              from,
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
            from,
            from,
            content,
            textual,
            false,
            attachments));
  }

  static long timestampEpochMs(JsonNode message) {
    if (message == null) {
      return 0L;
    }
    String raw = message.path("timestamp").asText("");
    if (raw.isBlank()) {
      return 0L;
    }
    try {
      return Long.parseLong(raw) * 1000L;
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  static String firstFrom(JsonNode root) {
    if (root == null) {
      return null;
    }
    JsonNode entries = root.path("entry");
    if (!entries.isArray()) {
      return null;
    }
    for (JsonNode entry : entries) {
      for (JsonNode change : entry.path("changes")) {
        for (JsonNode message : change.path("value").path("messages")) {
          String from = text(message, "from");
          if (from != null) {
            return from;
          }
        }
      }
    }
    return null;
  }

  static long firstTimestampMs(JsonNode root) {
    if (root == null) {
      return 0L;
    }
    JsonNode entries = root.path("entry");
    if (!entries.isArray()) {
      return 0L;
    }
    for (JsonNode entry : entries) {
      for (JsonNode change : entry.path("changes")) {
        for (JsonNode message : change.path("value").path("messages")) {
          long ts = timestampEpochMs(message);
          if (ts > 0L) {
            return ts;
          }
        }
      }
    }
    return 0L;
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
