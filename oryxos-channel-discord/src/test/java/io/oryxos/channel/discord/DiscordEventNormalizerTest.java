package io.oryxos.channel.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiscordEventNormalizerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String APP_ID = "123456789012345678";
  private DiscordEventNormalizer normalizer;

  @BeforeEach
  void setUp() {
    normalizer = new DiscordEventNormalizer("ops-discord", APP_ID);
  }

  @Test
  @DisplayName("私聊 MESSAGE_CREATE（无 guild_id）→ P2P")
  void dmMessage() {
    ObjectNode data = baseMessage("hello discord", null);
    Optional<InboundMessage> msg = normalizer.normalize("MESSAGE_CREATE", data);
    assertTrue(msg.isPresent());
    InboundMessage m = msg.get();
    assertEquals(ChatKind.P2P, m.chatKind());
    assertEquals("hello discord", m.content());
    assertEquals("channel-1", m.chatId());
    assertEquals("user-1", m.userId());
    assertFalse(m.mentionedBot());
  }

  @Test
  @DisplayName("公会 @Bot → GROUP 且剥离提及")
  void guildMention() {
    ObjectNode data = baseMessage("<@" + APP_ID + "> 帮我查天气", "guild-1");
    var mentions = data.putArray("mentions");
    mentions.addObject().put("id", APP_ID).put("username", "oryxos");
    Optional<InboundMessage> msg = normalizer.normalize("MESSAGE_CREATE", data);
    assertTrue(msg.isPresent());
    InboundMessage m = msg.get();
    assertEquals(ChatKind.GROUP, m.chatKind());
    assertTrue(m.mentionedBot());
    assertEquals("帮我查天气", m.content());
  }

  @Test
  @DisplayName("公会无 @ 丢弃")
  void guildWithoutMentionDropped() {
    ObjectNode data = baseMessage("noise", "guild-1");
    assertTrue(normalizer.normalize("MESSAGE_CREATE", data).isEmpty());
  }

  @Test
  @DisplayName("bot / webhook 丢弃")
  void botAndWebhookDropped() {
    ObjectNode bot = baseMessage("from bot", null);
    bot.path("author").path("bot"); // ensure author exists
    ((ObjectNode) bot.get("author")).put("bot", true);
    assertTrue(normalizer.normalize("MESSAGE_CREATE", bot).isEmpty());

    ObjectNode webhook = baseMessage("hook", null);
    webhook.put("webhook_id", "wh-1");
    assertTrue(normalizer.normalize("MESSAGE_CREATE", webhook).isEmpty());
  }

  @Test
  @DisplayName("非 MESSAGE_CREATE 忽略")
  void otherEventsIgnored() {
    ObjectNode data = baseMessage("x", null);
    assertTrue(normalizer.normalize("MESSAGE_UPDATE", data).isEmpty());
  }

  private static ObjectNode baseMessage(String content, String guildId) {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("id", "msg-1");
    data.put("channel_id", "channel-1");
    data.put("content", content);
    if (guildId != null) {
      data.put("guild_id", guildId);
    }
    ObjectNode author = data.putObject("author");
    author.put("id", "user-1");
    author.put("username", "richard");
    author.put("bot", false);
    return data;
  }
}
