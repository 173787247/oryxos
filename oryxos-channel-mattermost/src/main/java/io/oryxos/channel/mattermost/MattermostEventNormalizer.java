package io.oryxos.channel.mattermost;

import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Mattermost Outgoing Webhook 表单 → {@link InboundMessage}。频道需 trigger_word 或 {@code @bot}。 */
public class MattermostEventNormalizer {

  static final String CHANNEL_TYPE = "mattermost";
  private static final Pattern MENTION = Pattern.compile("@[A-Za-z0-9._-]+\\s*");

  private final String channelName;
  private final String botUsername;

  public MattermostEventNormalizer(String channelName, String botUsername) {
    this.channelName = channelName;
    this.botUsername = botUsername == null ? "" : botUsername.strip();
  }

  public Optional<InboundMessage> normalize(String formBody) {
    Map<String, String> form = parseForm(formBody);
    String messageId = firstNonBlank(form.get("post_id"), form.get("id"));
    String userId = firstNonBlank(form.get("user_id"), form.get("user_name"));
    String chatId = firstNonBlank(form.get("channel_id"), form.get("channel_name"));
    if (messageId == null || userId == null || chatId == null) {
      return Optional.empty();
    }
    String text = form.getOrDefault("text", "").strip();
    String trigger = form.getOrDefault("trigger_word", "");
    String channelType = form.getOrDefault("channel_type", "");
    boolean dm =
        "D".equalsIgnoreCase(channelType) || form.getOrDefault("channel_name", "").startsWith("@");
    if (!dm) {
      if (trigger.isBlank() && !mentionsBot(text)) {
        return Optional.empty();
      }
      text = stripMention(text);
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

  boolean tokenMatches(String formBody, String expected) {
    if (expected == null || expected.isBlank()) {
      return false;
    }
    return expected.equals(parseForm(formBody).get("token"));
  }

  private boolean mentionsBot(String text) {
    if (botUsername.isBlank() || text == null) {
      return false;
    }
    return text.toLowerCase(Locale.ROOT).contains("@" + botUsername.toLowerCase(Locale.ROOT));
  }

  private String stripMention(String text) {
    return text == null ? "" : MENTION.matcher(text).replaceAll("").strip();
  }

  static Map<String, String> parseForm(String body) {
    Map<String, String> out = new LinkedHashMap<>();
    if (body == null || body.isBlank()) {
      return out;
    }
    for (String pair : body.split("&")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String key = urlDecode(pair.substring(0, eq));
      String value = urlDecode(pair.substring(eq + 1));
      out.put(key, value);
    }
    return out;
  }

  private static String urlDecode(String value) {
    return URLDecoder.decode(value.replace('+', ' '), StandardCharsets.UTF_8);
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) {
      return a;
    }
    if (b != null && !b.isBlank()) {
      return b;
    }
    return null;
  }
}
