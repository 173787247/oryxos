package io.oryxos.channel.slack;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Slack Events API 事件 → 归一化 {@link InboundMessage}（MVP：仅文本私聊 / 群 {@code app_mention}）。
 *
 * <p>群聊只接受 {@code app_mention}；普通群消息丢弃。忽略 bot 自身消息与带 subtype 的系统编辑帧。
 */
public class SlackEventNormalizer {

  private static final Logger LOG = LoggerFactory.getLogger(SlackEventNormalizer.class);

  static final String CHANNEL_TYPE = "slack";
  private static final String EVENT_MESSAGE = "message";
  private static final String EVENT_APP_MENTION = "app_mention";
  private static final String CHANNEL_TYPE_IM = "im";
  private static final String CHANNEL_TYPE_MPIM = "mpim";
  private static final Pattern MENTION = Pattern.compile("<@[A-Z0-9]+>\\s*");

  private final String channelName;

  public SlackEventNormalizer(String channelName) {
    this.channelName = channelName;
  }

  /** 归一化一条 Events API {@code event} 对象；不支持或结构不完整返回 empty。 */
  public Optional<InboundMessage> normalize(JsonNode event) {
    if (event == null || !event.isObject()) {
      return Optional.empty();
    }
    String type = text(event, "type");
    if (type == null) {
      return Optional.empty();
    }
    if (EVENT_APP_MENTION.equals(type)) {
      return normalizeAppMention(event);
    }
    if (EVENT_MESSAGE.equals(type)) {
      return normalizeMessage(event);
    }
    LOG.info("Slack 收到暂不支持的事件类型 type={}", sanitize(type));
    return Optional.empty();
  }

  private Optional<InboundMessage> normalizeAppMention(JsonNode event) {
    if (isBotOrEdited(event)) {
      return Optional.empty();
    }
    String userId = text(event, "user");
    String chatId = text(event, "channel");
    String messageId = text(event, "ts");
    if (userId == null || chatId == null || messageId == null) {
      LOG.warn("Slack app_mention 缺关键字段（user/channel/ts），已丢弃");
      return Optional.empty();
    }
    String content = stripMentions(event.path("text").asText("")).strip();
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            messageId,
            ChatKind.GROUP,
            userId,
            chatId,
            content,
            true,
            true,
            List.of()));
  }

  private Optional<InboundMessage> normalizeMessage(JsonNode event) {
    if (isBotOrEdited(event)) {
      return Optional.empty();
    }
    String channelType = text(event, "channel_type");
    boolean dm = CHANNEL_TYPE_IM.equals(channelType) || CHANNEL_TYPE_MPIM.equals(channelType);
    if (!dm) {
      // 频道内普通 message 等 app_mention；避免重复处理
      return Optional.empty();
    }
    String userId = text(event, "user");
    String chatId = text(event, "channel");
    String messageId = text(event, "ts");
    if (userId == null || chatId == null || messageId == null) {
      LOG.warn("Slack 私聊消息缺关键字段（user/channel/ts），已丢弃");
      return Optional.empty();
    }
    String content = event.path("text").asText("").strip();
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            messageId,
            ChatKind.P2P,
            userId,
            chatId,
            content,
            true,
            false,
            List.of()));
  }

  private static boolean isBotOrEdited(JsonNode event) {
    if (event.hasNonNull("bot_id")) {
      return true;
    }
    String subtype = text(event, "subtype");
    return subtype != null && !subtype.isBlank();
  }

  static String stripMentions(String text) {
    if (text == null || text.isBlank()) {
      return "";
    }
    return MENTION.matcher(text).replaceAll("").strip();
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    if (v == null || v.isNull()) {
      return null;
    }
    String s = v.asText();
    return s == null || s.isBlank() ? null : s;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
