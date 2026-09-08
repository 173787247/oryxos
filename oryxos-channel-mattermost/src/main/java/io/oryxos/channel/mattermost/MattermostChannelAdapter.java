package io.oryxos.channel.mattermost;

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
import java.util.Optional;

/**
 * Mattermost 入站：Outgoing Webhook。{@code app_id}=Bot 用户名，{@code app_secret}=webhook token + 发贴
 * token，{@code extra.base_url}。
 */
public class MattermostChannelAdapter implements InboundChannelAdapter, InboundWebhookHandler {

  public static final String TYPE = "mattermost";
  private static final String EXTRA_BASE_URL = "base_url";
  private static final int HTTP_UNAUTHORIZED = 401;

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;

  private volatile MattermostEventNormalizer normalizer;
  private volatile MattermostMessageSender sender;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者均为 Runtime 装配的单例，共享引用正是意图")
  public MattermostChannelAdapter(
      ChannelConfig config,
      ProfileRegistry profileRegistry,
      InboundMessageService inboundMessageService,
      OutboundGuard guard) {
    this.config = config;
    this.profileRegistry = profileRegistry;
    this.inboundMessageService = inboundMessageService;
    this.guard = guard;
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
    if (config.extra(EXTRA_BASE_URL) == null || config.extra(EXTRA_BASE_URL).isBlank()) {
      throw new IllegalArgumentException("渠道 " + config.name() + " 缺少 extra.base_url");
    }
    if (profileRegistry.get(config.agent()).isEmpty()) {
      throw new IllegalArgumentException(
          "渠道 " + config.name() + " 绑定的 Agent " + config.agent() + " 不存在");
    }
    guard.check(config.extra(EXTRA_BASE_URL));
    normalizer = new MattermostEventNormalizer(config.name(), config.appId());
    sender = new MattermostMessageSender(guard, config.extra(EXTRA_BASE_URL), config.appSecret());
    state = ChannelStatus.State.CONNECTED;
  }

  @Override
  public synchronized void stop() {
    state = ChannelStatus.State.DISCONNECTED;
  }

  @Override
  public ChannelStatus status() {
    return ChannelStatus.ok(name(), TYPE, boundAgent(), state);
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    MattermostMessageSender current = sender;
    if (current == null) {
      throw new IllegalStateException("渠道 " + name() + " 尚未启动");
    }
    current.send(chatId, text, replyToMessageId);
  }

  @Override
  public WebhookResponse onWebhook(WebhookRequest request) {
    if (normalizer == null || !normalizer.tokenMatches(request.body(), config.appSecret())) {
      return WebhookResponse.text(HTTP_UNAUTHORIZED, "invalid token");
    }
    Optional<InboundMessage> msg = normalizer.normalize(request.body());
    msg.ifPresent(m -> inboundMessageService.onMessage(m, this));
    return WebhookResponse.ok();
  }
}
