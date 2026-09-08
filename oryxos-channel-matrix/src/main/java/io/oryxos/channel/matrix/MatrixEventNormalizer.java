package io.oryxos.channel.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Matrix {@code m.room.message} → {@link InboundMessage}。房间需提及 bot user id。 */
public class MatrixEventNormalizer {

  static final String CHANNEL_TYPE = "matrix";
  private static final String TYPE_ROOM_MESSAGE = "m.room.message";
  private static final String FIELD_TYPE = "type";
  private static final String FIELD_SENDER = "sender";
  private static final String FIELD_EVENT_ID = "event_id";
  private static final String FIELD_CONTENT = "content";
  private static final String FIELD_MSGTYPE = "msgtype";
  private static final String FIELD_BODY = "body";
  private static final String FIELD_URL = "url";
  private static final String MSG_IMAGE = "m.image";
  private static final String MSG_AUDIO = "m.audio";
  private static final String MSG_VIDEO = "m.video";
  private static final String MSG_FILE = "m.file";
  private static final String FIELD_MENTIONS = "m.mentions";
  private static final String FIELD_USER_IDS = "user_ids";
  private static final String DEFAULT_MXC = "mxc";

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
    if (!TYPE_ROOM_MESSAGE.equals(event.path(FIELD_TYPE).asText(""))) {
      return Optional.empty();
    }
    String sender = text(event, FIELD_SENDER);
    String eventId = text(event, FIELD_EVENT_ID);
    if (sender == null || eventId == null || roomId == null || roomId.isBlank()) {
      return Optional.empty();
    }
    if (!botUserId.isBlank() && botUserId.equals(sender)) {
      return Optional.empty();
    }
    JsonNode content = event.path(FIELD_CONTENT);
    String msgtype = content.path(FIELD_MSGTYPE).asText("");
    String body = content.path(FIELD_BODY).asText("").strip();
    List<InboundAttachment> attachments = new ArrayList<>();
    if (MSG_IMAGE.equals(msgtype)) {
      attachments.add(
          InboundAttachment.imageReference(content.path(FIELD_URL).asText(DEFAULT_MXC)));
    } else if (MSG_AUDIO.equals(msgtype)) {
      attachments.add(
          InboundAttachment.audioReference(content.path(FIELD_URL).asText(DEFAULT_MXC)));
    } else if (MSG_VIDEO.equals(msgtype)) {
      attachments.add(
          InboundAttachment.videoReference(content.path(FIELD_URL).asText(DEFAULT_MXC)));
    } else if (MSG_FILE.equals(msgtype)) {
      attachments.add(InboundAttachment.fileReference(content.path(FIELD_URL).asText(DEFAULT_MXC)));
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
    JsonNode mentions = content.path(FIELD_MENTIONS).path(FIELD_USER_IDS);
    if (mentions.isArray()) {
      for (JsonNode id : mentions) {
        if (botUserId.equals(id.asText())) {
          return true;
        }
      }
    }
    return body != null && asciiLower(body).contains(asciiLower(botUserId));
  }

  private String stripBot(String body) {
    if (body == null || botUserId.isBlank()) {
      return body == null ? "" : body;
    }
    return body.replace(botUserId, "").strip();
  }

  private static String asciiLower(String value) {
    if (value == null) {
      return "";
    }
    char[] chars = value.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      char c = chars[i];
      if (c >= 'A' && c <= 'Z') {
        chars[i] = (char) (c + ('a' - 'A'));
      }
    }
    return new String(chars);
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
