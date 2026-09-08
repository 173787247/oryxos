package io.oryxos.channel.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Telegram {@code Update.message} → {@link InboundMessage}。群聊仅当 {@code @} bot 用户名时接受。 */
public class TelegramEventNormalizer {

  static final String CHANNEL_TYPE = "telegram";
  private static final Pattern MENTION = Pattern.compile("@[A-Za-z0-9_]+\\s*");

  private final String channelName;
  private final String botUsername;

  public TelegramEventNormalizer(String channelName, String botUsername) {
    this.channelName = channelName;
    this.botUsername = botUsername == null ? "" : stripAt(botUsername);
  }

  public Optional<InboundMessage> normalize(JsonNode update) {
    if (update == null || !update.isObject()) {
      return Optional.empty();
    }
    JsonNode message = update.path("message");
    if (message.isMissingNode() || !message.isObject()) {
      message = update.path("edited_message");
    }
    if (!message.isObject()) {
      return Optional.empty();
    }
    JsonNode from = message.path("from");
    if (from.path("is_bot").asBoolean(false)) {
      return Optional.empty();
    }
    String userId = text(from, "id");
    JsonNode chat = message.path("chat");
    String chatId = text(chat, "id");
    String messageId = text(message, "message_id");
    if (userId == null || chatId == null || messageId == null) {
      return Optional.empty();
    }
    String chatType = chat.path("type").asText("");
    boolean group = "group".equals(chatType) || "supergroup".equals(chatType);
    String text = message.path("text").asText("");
    if (text.isBlank()) {
      text = message.path("caption").asText("");
    }
    List<InboundAttachment> attachments = extractAttachments(message);
    if (group) {
      if (!mentionsBot(message, text)) {
        return Optional.empty();
      }
      text = stripBotMention(text).strip();
      if (text.isBlank() && attachments.isEmpty()) {
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
              !text.isBlank(),
              true,
              attachments));
    }
    if (text.isBlank() && attachments.isEmpty()) {
      if (message.has("sticker") || message.has("location")) {
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
      return Optional.empty();
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
            !text.isBlank(),
            false,
            attachments));
  }

  private boolean mentionsBot(JsonNode message, String text) {
    if (botUsername.isBlank()) {
      return false;
    }
    String needle = "@" + botUsername.toLowerCase(Locale.ROOT);
    if (text != null && text.toLowerCase(Locale.ROOT).contains(needle)) {
      return true;
    }
    JsonNode entities = message.path("entities");
    if (!entities.isArray()) {
      entities = message.path("caption_entities");
    }
    if (entities.isArray()) {
      for (JsonNode entity : entities) {
        String type = entity.path("type").asText("");
        if ("mention".equals(type) && text != null) {
          int offset = entity.path("offset").asInt(0);
          int length = entity.path("length").asInt(0);
          if (offset >= 0 && offset + length <= text.length()) {
            String frag = text.substring(offset, offset + length).toLowerCase(Locale.ROOT);
            if (frag.equals(needle)) {
              return true;
            }
          }
        }
        if ("text_mention".equals(type)
            && botUsername.equalsIgnoreCase(entity.path("user").path("username").asText(""))) {
          return true;
        }
      }
    }
    return false;
  }

  private String stripBotMention(String text) {
    if (text == null || text.isBlank() || botUsername.isBlank()) {
      return text == null ? "" : text;
    }
    return MENTION.matcher(text).replaceAll("").strip();
  }

  static List<InboundAttachment> extractAttachments(JsonNode message) {
    List<InboundAttachment> out = new ArrayList<>();
    JsonNode photos = message.path("photo");
    if (photos.isArray() && photos.size() > 0) {
      JsonNode best = photos.get(photos.size() - 1);
      String fileId = text(best, "file_id");
      if (fileId != null) {
        out.add(InboundAttachment.imageReference(fileId));
      }
    }
    JsonNode voice = message.path("voice");
    if (voice.isObject()) {
      String fileId = text(voice, "file_id");
      if (fileId != null) {
        out.add(InboundAttachment.audioReference(fileId));
      }
    }
    JsonNode audio = message.path("audio");
    if (audio.isObject()) {
      String fileId = text(audio, "file_id");
      if (fileId != null) {
        out.add(
            new InboundAttachment(
                InboundAttachment.TYPE_AUDIO, null, fileId, text(audio, "file_name")));
      }
    }
    JsonNode document = message.path("document");
    if (document.isObject()) {
      String fileId = text(document, "file_id");
      if (fileId != null) {
        String mime = document.path("mime_type").asText("");
        String name = text(document, "file_name");
        if (mime.startsWith("image/")) {
          out.add(new InboundAttachment(InboundAttachment.TYPE_IMAGE, null, fileId, name));
        } else if (mime.startsWith("audio/")) {
          out.add(new InboundAttachment(InboundAttachment.TYPE_AUDIO, null, fileId, name));
        } else if (mime.startsWith("video/")) {
          out.add(new InboundAttachment(InboundAttachment.TYPE_VIDEO, null, fileId, name));
        } else {
          out.add(InboundAttachment.fileReference(fileId, name));
        }
      }
    }
    JsonNode video = message.path("video");
    if (video.isObject()) {
      String fileId = text(video, "file_id");
      if (fileId != null) {
        out.add(InboundAttachment.videoReference(fileId, text(video, "file_name")));
      }
    }
    return out;
  }

  private static String stripAt(String username) {
    String s = username.strip();
    return s.startsWith("@") ? s.substring(1) : s;
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
