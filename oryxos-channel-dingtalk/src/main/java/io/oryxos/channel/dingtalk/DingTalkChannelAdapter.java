package io.oryxos.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.ProfileRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 钉钉机器人入站适配器：一实例 = 一个应用的一条 Stream 长连接（对称飞书/企微）。
 *
 * <p>凭证映射：{@code app_id} = ClientId，{@code app_secret} = ClientSecret。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR",
    justification = "stream/normalizer/sender 在 start() 内初始化；sendReply 有显式空判。")
public class DingTalkChannelAdapter implements InboundChannelAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(DingTalkChannelAdapter.class);

  public static final String TYPE = "dingtalk";

  private static final Duration START_TIMEOUT = Duration.ofSeconds(20);

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;

  private final AtomicReference<DingTalkStreamClient> streamRef = new AtomicReference<>();
  private volatile DingTalkMessageSender sender;
  private volatile DingTalkEventNormalizer normalizer;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;
  private volatile String lastError;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者均为 Runtime 装配的单例，共享引用正是意图")
  public DingTalkChannelAdapter(
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
    if (profileRegistry.get(config.agent()).isEmpty()) {
      throw new IllegalArgumentException(
          "渠道 " + config.name() + " 绑定的 Agent " + config.agent() + " 不存在");
    }
    guard.check(DingTalkStreamClient.API_BASE_URL);
    guard.check(DingTalkMessageSender.SESSION_WEBHOOK_PREFIX);
    try {
      normalizer = new DingTalkEventNormalizer(config.name());
      sender = new DingTalkMessageSender(guard, DingTalkMessageSender.DEFAULT_CHUNK_SIZE);
      DingTalkStreamClient client =
          new DingTalkStreamClient(
              config.appId(),
              config.appSecret(),
              guard,
              this::handleBotMessage,
              () -> {
                if (state != ChannelStatus.State.ERROR) {
                  state = ChannelStatus.State.DISCONNECTED;
                }
              });
      client.connect(START_TIMEOUT);
      streamRef.set(client);
      state = ChannelStatus.State.CONNECTED;
      lastError = null;
      LOG.info("钉钉渠道 {} Stream 已建立（Agent: {}）", sanitize(config.name()), sanitize(config.agent()));
    } catch (Exception e) {
      state = ChannelStatus.State.ERROR;
      lastError = "Stream 连接失败: " + sanitize(e.getMessage());
      throw new IllegalStateException("渠道 " + config.name() + " " + lastError, e);
    }
  }

  @Override
  public synchronized void stop() {
    DingTalkStreamClient stream = streamRef.getAndSet(null);
    if (stream != null) {
      stream.closeQuietly();
    }
    state = ChannelStatus.State.DISCONNECTED;
  }

  @Override
  public ChannelStatus status() {
    if (state == ChannelStatus.State.ERROR) {
      return ChannelStatus.error(config.name(), TYPE, config.agent(), lastError);
    }
    DingTalkStreamClient stream = streamRef.get();
    if (stream == null || !stream.isConnected()) {
      return ChannelStatus.ok(
          config.name(), TYPE, config.agent(), ChannelStatus.State.DISCONNECTED);
    }
    return ChannelStatus.ok(config.name(), TYPE, config.agent(), ChannelStatus.State.CONNECTED);
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    DingTalkMessageSender active = sender;
    if (active == null) {
      throw new IllegalStateException("渠道 " + config.name() + " 未启动，无法发送回复");
    }
    active.send(chatId, text, replyToMessageId);
  }

  private void handleBotMessage(JsonNode body) {
    try {
      String conversationId = body.path("conversationId").asText(null);
      String sessionWebhook = body.path("sessionWebhook").asText(null);
      String atUserId = body.path("senderStaffId").asText(null);
      if (atUserId == null || atUserId.isBlank()) {
        atUserId = body.path("senderId").asText(null);
      }
      sender.rememberSession(conversationId, sessionWebhook, atUserId);
      Optional<InboundMessage> msg = normalizer.normalize(body);
      msg.ifPresent(m -> inboundMessageService.onMessage(m, this));
    } catch (RuntimeException e) {
      LOG.error("钉钉渠道 {} 事件处理异常: {}", sanitize(config.name()), sanitize(e.getMessage()));
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
