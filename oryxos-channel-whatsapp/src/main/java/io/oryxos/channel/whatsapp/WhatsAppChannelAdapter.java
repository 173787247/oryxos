package io.oryxos.channel.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.InboundWebhookHandler;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.channel.WebhookRequest;
import io.oryxos.core.channel.WebhookResponse;
import io.oryxos.core.profile.ProfileRegistry;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * WhatsApp Cloud API 入站：共享 webhook + Graph 发信。会话窗外 {@link #sendReply} 硬拒绝，不静默。
 *
 * <p>{@code app_id}=access token，{@code app_secret}=App Secret（X-Hub-Signature-256），{@code
 * extra.verify_token}、{@code extra.phone_number_id}。
 */
public class WhatsAppChannelAdapter implements InboundChannelAdapter, InboundWebhookHandler {

  public static final String TYPE = "whatsapp";
  static final Duration SESSION_WINDOW = Duration.ofHours(24);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;
  private final Clock clock;
  private final ConcurrentHashMap<String, Long> lastInboundMs = new ConcurrentHashMap<>();

  private volatile WhatsAppEventNormalizer normalizer;
  private volatile WhatsAppMessageSender sender;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;
  private volatile String lastError;

  public WhatsAppChannelAdapter(
      ChannelConfig config,
      ProfileRegistry profileRegistry,
      InboundMessageService inboundMessageService,
      OutboundGuard guard) {
    this(config, profileRegistry, inboundMessageService, guard, Clock.systemUTC());
  }

  WhatsAppChannelAdapter(
      ChannelConfig config,
      ProfileRegistry profileRegistry,
      InboundMessageService inboundMessageService,
      OutboundGuard guard,
      Clock clock) {
    this.config = config;
    this.profileRegistry = profileRegistry;
    this.inboundMessageService = inboundMessageService;
    this.guard = guard;
    this.clock = clock;
  }

  @Override
  public String name() {
    return config.name();
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public String boundAgent() {
    return config.agent();
  }

  @Override
  public synchronized void start() {
    config.validateCredentialsResolved();
    requireExtra("verify_token");
    requireExtra("phone_number_id");
    if (profileRegistry.get(config.agent()).isEmpty()) {
      throw new IllegalArgumentException(
          "渠道 " + config.name() + " 绑定的 Agent " + config.agent() + " 不存在");
    }
    guard.check(WhatsAppMessageSender.DEFAULT_GRAPH_BASE);
    normalizer = new WhatsAppEventNormalizer(config.name());
    sender = new WhatsAppMessageSender(guard, config.appId(), config.extra("phone_number_id"));
    state = ChannelStatus.State.CONNECTED;
    lastError = null;
  }

  @Override
  public synchronized void stop() {
    state = ChannelStatus.State.DISCONNECTED;
  }

  @Override
  public ChannelStatus status() {
    if (state == ChannelStatus.State.ERROR) {
      return ChannelStatus.error(name(), TYPE, boundAgent(), lastError);
    }
    return ChannelStatus.ok(name(), TYPE, boundAgent(), state);
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    Long last = lastInboundMs.get(chatId);
    if (last == null || clock.millis() - last > SESSION_WINDOW.toMillis()) {
      throw new IllegalStateException(
          "WhatsApp 24 小时会话窗已关闭，只能发送已审核模板消息，拒绝静默投递（chat=" + chatId + "）");
    }
    WhatsAppMessageSender current = sender;
    if (current == null) {
      throw new IllegalStateException("渠道 " + name() + " 尚未启动");
    }
    current.send(chatId, text);
  }

  @Override
  public WebhookResponse onWebhook(WebhookRequest request) {
    if ("GET".equalsIgnoreCase(request.method())) {
      return handleChallenge(request);
    }
    if (!verifySignature(request.body(), request.header("x-hub-signature-256"))) {
      return WebhookResponse.text(401, "invalid signature");
    }
    try {
      JsonNode root = MAPPER.readTree(request.body().isBlank() ? "{}" : request.body());
      String from = WhatsAppEventNormalizer.firstFrom(root);
      long ts = WhatsAppEventNormalizer.firstTimestampMs(root);
      if (from != null) {
        lastInboundMs.put(from, ts > 0L ? ts : clock.millis());
      }
      List<InboundMessage> messages = normalizer == null ? List.of() : normalizer.normalize(root);
      for (InboundMessage message : messages) {
        inboundMessageService.onMessage(message, this);
      }
      return WebhookResponse.ok();
    } catch (Exception e) {
      return WebhookResponse.text(400, "bad payload");
    }
  }

  void rememberInbound(String chatId, long epochMs) {
    lastInboundMs.put(chatId, epochMs);
  }

  private WebhookResponse handleChallenge(WebhookRequest request) {
    String mode = request.query("hub.mode");
    String token = request.query("hub.verify_token");
    String challenge = request.query("hub.challenge");
    if ("subscribe".equals(mode)
        && token != null
        && token.equals(config.extra("verify_token"))
        && challenge != null) {
      return WebhookResponse.text(200, challenge);
    }
    return WebhookResponse.text(403, "verify failed");
  }

  private boolean verifySignature(String body, String header) {
    if (header == null || !header.toLowerCase(Locale.ROOT).startsWith("sha256=")) {
      return false;
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(config.appSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String expected =
          "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
      return header.equalsIgnoreCase(expected);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  private void requireExtra(String key) {
    String value = config.extra(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("渠道 " + config.name() + " 缺少 extra." + key);
    }
  }
}
