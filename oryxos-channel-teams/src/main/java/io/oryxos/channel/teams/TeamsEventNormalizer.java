package io.oryxos.channel.teams;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Azure Bot Framework Activity → {@link InboundMessage}。频道仅当 @Bot。MVP 纯文本。 */
public class TeamsEventNormalizer {

  static final String CHANNEL_TYPE = "teams";
  private static final Pattern AT_TAG = Pattern.compile("(?i)<at>[^<]*</at>\\s*");

  private final String channelName;
  private final String appId;

  public TeamsEventNormalizer(String channelName, String appId) {
    this.channelName = channelName;
    this.appId = appId == null ? "" : appId.strip();
  }

  public Optional<InboundMessage> normalize(JsonNode activity) {
    if (activity == null || !activity.isObject()) {
      return Optional.empty();
    }
    if (!"message".equals(activity.path("type").asText(""))) {
      return Optional.empty();
    }
    String messageId = text(activity, "id");
    String userId = text(activity.path("from"), "id");
    String chatId = text(activity.path("conversation"), "id");
    if (messageId == null || userId == null || chatId == null) {
      return Optional.empty();
    }
    String convType =
        activity.path("conversation").path("conversationType").asText("").toLowerCase(Locale.ROOT);
    boolean group = "channel".equals(convType) || "groupchat".equals(convType);
    String text = activity.path("text").asText("").strip();
    if (group) {
      if (!mentionsBot(activity, text)) {
        return Optional.empty();
      }
      text = AT_TAG.matcher(text).replaceAll("").strip();
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

  static String serviceUrl(JsonNode activity) {
    return activity == null ? null : text(activity, "serviceUrl");
  }

  private boolean mentionsBot(JsonNode activity, String text) {
    JsonNode entities = activity.path("entities");
    if (entities.isArray()) {
      for (JsonNode entity : entities) {
        if (!"mention".equals(entity.path("type").asText(""))) {
          continue;
        }
        String mentioned = text(entity.path("mentioned"), "id");
        if (mentioned != null && (mentioned.contains(appId) || mentioned.equals(appId))) {
          return true;
        }
      }
    }
    return text != null && text.toLowerCase(Locale.ROOT).contains("<at>");
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
