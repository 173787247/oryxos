package io.oryxos.channel.teams;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.channel.BoundedMap;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundCallerVerifier;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.InboundWebhookHandler;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.channel.WebhookRequest;
import io.oryxos.core.channel.WebhookResponse;
import io.oryxos.core.profile.ProfileRegistry;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Microsoft Teams 入站：Azure Bot webhook。凭证 {@code app_id}/{@code app_secret}/{@code
 * extra.tenant_id}。
 */
public class TeamsChannelAdapter implements InboundChannelAdapter, InboundWebhookHandler {

  public static final String TYPE = "teams";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String EXTRA_TENANT_ID = "tenant_id";
  private static final String FIELD_CONVERSATION = "conversation";
  private static final String FIELD_ID = "id";
  private static final int HTTP_BAD_REQUEST = 400;
  private static final int HTTP_UNAUTHORIZED = 401;
  private static final String AUTHORIZATION_HEADER = "authorization";

  /** 最多记住多少个会话的 {@code serviceUrl}；超出按最早淘汰（与微信渠道那份同口径）。 */
  static final int MAX_TRACKED_CONVERSATIONS = 1000;

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;
  private final Map<String, String> serviceUrls =
      Collections.synchronizedMap(new BoundedMap<>(MAX_TRACKED_CONVERSATIONS));

  private volatile TeamsEventNormalizer normalizer;
  private volatile TeamsMessageSender sender;
  private volatile TeamsCallerJwt callerJwt;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者均为 Runtime 装配的单例，共享引用正是意图")
  public TeamsChannelAdapter(
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
    if (config.extra(EXTRA_TENANT_ID) == null || config.extra(EXTRA_TENANT_ID).isBlank()) {
      throw new IllegalArgumentException("渠道 " + config.name() + " 缺少 extra.tenant_id");
    }
    if (profileRegistry.get(config.agent()).isEmpty()) {
      throw new IllegalArgumentException(
          "渠道 " + config.name() + " 绑定的 Agent " + config.agent() + " 不存在");
    }
    guard.check(TeamsMessageSender.LOGIN_HOST);
    normalizer = new TeamsEventNormalizer(config.name(), config.appId());
    sender =
        new TeamsMessageSender(
            guard, config.appId(), config.appSecret(), config.extra(EXTRA_TENANT_ID));
    callerJwt = callerJwtFor(config);
    state = ChannelStatus.State.CONNECTED;
  }

  /** {@code extra.verify_caller_jwt=true} 才启用官方 JWT 校验；audience 用本 Bot 的 appId。 */
  private static TeamsCallerJwt callerJwtFor(ChannelConfig config) {
    return Boolean.parseBoolean(config.extra(TeamsCallerJwt.EXTRA_VERIFY_CALLER_JWT))
        ? new TeamsCallerJwt(config.appId())
        : null;
  }

  /**
   * 官方 JWT 优先（配了 {@code verify_caller_jwt=true} 才启用）；否则退回共享 token 闸（{@code
   * extra.inbound_token}）。两者都没配则放行 —— 那正是 {@code ChannelAdminService} 启动告警点名的情形。
   */
  private boolean callerAccepted(WebhookRequest request) {
    TeamsCallerJwt verifier = callerJwt;
    if (verifier != null && !verifier.accepts(request.header(AUTHORIZATION_HEADER))) {
      return false;
    }
    return InboundCallerVerifier.accepts(
        config.extra(InboundCallerVerifier.EXTRA_INBOUND_TOKEN), request);
  }

  @Override
  public synchronized void stop() {
    state = ChannelStatus.State.DISCONNECTED;
  }

  @Override
  public ChannelStatus status() {
    return ChannelStatus.ok(name(), TYPE, boundAgent(), state);
  }

  /** 官方 JWT 或共享 token 任一配好即算有校验；都没有则集群档会被装配层拒绝上线。 */
  @Override
  public boolean callerVerificationConfigured() {
    String token = config.extra(InboundCallerVerifier.EXTRA_INBOUND_TOKEN);
    return callerJwt != null || (token != null && !token.isBlank());
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    TeamsMessageSender current = sender;
    if (current == null) {
      throw new IllegalStateException("渠道 " + name() + " 尚未启动");
    }
    String serviceUrl = serviceUrls.get(chatId);
    if (serviceUrl == null || serviceUrl.isBlank()) {
      throw new IllegalStateException("渠道 " + name() + " 尚无会话 " + chatId + " 的 serviceUrl");
    }
    current.send(serviceUrl, chatId, text, replyToMessageId);
  }

  /** 仅供测试：当前记住的会话数。 */
  int trackedServiceUrlCount() {
    return serviceUrls.size();
  }

  /** 仅供测试：查一个会话记住的 serviceUrl。 */
  String serviceUrlFor(String chatId) {
    return serviceUrls.get(chatId);
  }

  @Override
  public WebhookResponse onWebhook(WebhookRequest request) {
    if (!callerAccepted(request)) {
      return WebhookResponse.text(HTTP_UNAUTHORIZED, "invalid caller token");
    }
    try {
      JsonNode activity = MAPPER.readTree(request.body().isBlank() ? "{}" : request.body());
      String serviceUrl = TeamsEventNormalizer.serviceUrl(activity);
      String chatId = activity.path(FIELD_CONVERSATION).path(FIELD_ID).asText("");
      if (serviceUrl != null && !chatId.isBlank()) {
        serviceUrls.put(chatId, serviceUrl);
      }
      Optional<InboundMessage> msg =
          normalizer == null ? Optional.empty() : normalizer.normalize(activity);
      msg.ifPresent(m -> inboundMessageService.onMessage(m, this));
      return WebhookResponse.ok();
    } catch (JacksonException e) {
      return WebhookResponse.text(HTTP_BAD_REQUEST, "bad payload");
    }
  }
}
