package io.oryxos.channel.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Matrix {@code m.room.message} → {@link InboundMessage}。房间需提及 bot user id。 */
public class MatrixEventNormalizer {

  static final String CHANNEL_TYPE = "matrix";

  private final String channelName;
  private final String botUserId;

  public MatrixEventNormalizer(String channelName, String botUserId) {
    this.channelName = channelName;
    this.botUserId = botUserId == null ? "" : botUserId.strip();
  }

  public Optional<InboundMessage> normalize(String roomId, JsonNode event, boolean direct) {
    if (event == null || !event.isObject()) {
      return Optional.empty();
    }
    if (!"m.room.message".equals(event.path("type").asText(""))) {
      return Optional.empty();
    }
    String sender = text(event, "sender");
    String eventId = text(event, "event_id");
    if (sender == null || eventId == null || roomId == null || roomId.isBlank()) {
      return Optional.empty();
    }
    if (!botUserId.isBlank() && botUserId.equals(sender)) {
      return Optional.empty();
    }
    JsonNode content = event.path("content");
    String msgtype = content.path("msgtype").asText("");
    String body = content.path("body").asText("").strip();
    List<InboundAttachment> attachments = new ArrayList<>();
    if ("m.image".equals(msgtype)) {
      attachments.add(InboundAttachment.imageReference(content.path("url").asText("mxc")));
    } else if ("m.audio".equals(msgtype)) {
      attachments.add(InboundAttachment.audioReference(content.path("url").asText("mxc")));
    } else if ("m.video".equals(msgtype)) {
      attachments.add(InboundAttachment.videoReference(content.path("url").asText("mxc")));
    } else if ("m.file".equals(msgtype)) {
      attachments.add(InboundAttachment.fileReference(content.path("url").asText("mxc")));
    }
    if (!direct) {
      if (!mentionsBot(content, body)) {
        return Optional.empty();
      }
      body = stripBot(body);
      if (body.isBlank() && attachments.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(
          new InboundMessage(
              CHANNEL_TYPE,
              channelName,
              eventId,
              ChatKind.GROUP,
              sender,
              roomId,
              body,
              !body.isBlank(),
              true,
              attachments));
    }
    if (body.isBlank() && attachments.isEmpty()) {
      return Optional.of(
          new InboundMessage(
              CHANNEL_TYPE,
              channelName,
              eventId,
              ChatKind.P2P,
              sender,
              roomId,
              "",
              false,
              false,
              List.of()));
    }
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            eventId,
            ChatKind.P2P,
            sender,
            roomId,
            body,
            !body.isBlank(),
            false,
            attachments));
  }

  private boolean mentionsBot(JsonNode content, String body) {
    if (botUserId.isBlank()) {
      return false;
    }
    JsonNode mentions = content.path("m.mentions").path("user_ids");
    if (mentions.isArray()) {
      for (JsonNode id : mentions) {
        if (botUserId.equals(id.asText())) {
          return true;
        }
      }
    }
    return body != null
        && body.toLowerCase(Locale.ROOT).contains(botUserId.toLowerCase(Locale.ROOT));
  }

  private String stripBot(String body) {
    if (body == null || botUserId.isBlank()) {
      return body == null ? "" : body;
    }
    return body.replace(botUserId, "").strip();
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
