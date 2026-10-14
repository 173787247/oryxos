package io.oryxos.channel.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.ProfileRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Telegram 入站：Bot API {@code getUpdates} 长轮询（免公网回调）。
 *
 * <p>凭证：{@code app_id}=Bot Token，{@code app_secret}=Bot 用户名（群 {@code @Bot} 匹配，可带或不带 @）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR",
    justification = "sender/normalizer 在 start() 内初始化；sendReply 有显式空判。")
public class TelegramChannelAdapter implements InboundChannelAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(TelegramChannelAdapter.class);

  public static final String TYPE = "telegram";
  static final String DEFAULT_API_BASE = "https://api.telegram.org";

  /**
   * {@code extra} 键：{@code getUpdates} 的长轮询秒数。
   *
   * <p>缺省 25 秒（Telegram 的常规用法）。**受限网络下要调小**：中间设备（企业代理、隧道）常把超过 ~20 秒 的空闲连接切断，表现为 {@code EOF reached
   * while reading} 而不是超时——轮询于是永远拿不到消息。实测把 它从 25 降到 10 之后，同一环境下失败次数从每 37 秒一次降到 0。
   */
  static final String EXTRA_POLL_TIMEOUT = "poll_timeout_seconds";

  static final int DEFAULT_POLL_TIMEOUT_SECONDS = 25;

  /** Telegram 对 {@code getUpdates} 的 {@code timeout} 参数上限是 50 秒，超过会被拒。 */
  static final int MAX_POLL_TIMEOUT_SECONDS = 50;

  static final int MIN_POLL_TIMEOUT_SECONDS = 1;

  /** HTTP 超时相对长轮询的余量：长轮询本身就要占满 poll 秒，超时必须更宽，否则每次都自杀。 */
  static final int HTTP_TIMEOUT_MARGIN_SECONDS = 10;

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FIELD_OK = "ok";
  private static final String FIELD_RESULT = "result";
  private static final String FIELD_UPDATE_ID = "update_id";

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;
  private final String apiBase;
  private final int pollTimeoutSeconds;
  private final Duration httpTimeout;
  private static final String MEDIA_DIR_PREFIX = "telegram";

  private volatile TelegramEventNormalizer normalizer;
  private volatile TelegramMessageSender sender;
  private volatile TelegramInboundMediaResolver mediaResolver;
  private volatile HttpClient http;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;
  private volatile String lastError;
  private volatile boolean running;
  private volatile Thread pollThread;
  private volatile long offset;

  public TelegramChannelAdapter(
      ChannelConfig config,
      ProfileRegistry profileRegistry,
      InboundMessageService inboundMessageService,
      OutboundGuard guard) {
    this(config, profileRegistry, inboundMessageService, guard, DEFAULT_API_BASE);
  }

  TelegramChannelAdapter(
      ChannelConfig config,
      ProfileRegistry profileRegistry,
      InboundMessageService inboundMessageService,
      OutboundGuard guard,
      String apiBase) {
    this.config = config;
    this.profileRegistry = profileRegistry;
    this.inboundMessageService = inboundMessageService;
    this.guard = guard;
    this.apiBase = apiBase;
    this.pollTimeoutSeconds = resolvePollTimeoutSeconds(config);
    this.httpTimeout = Duration.ofSeconds(pollTimeoutSeconds + HTTP_TIMEOUT_MARGIN_SECONDS);
  }

  /**
   * 解析 {@code extra.poll_timeout_seconds}：缺省、非法、越界都回落到缺省并告警——不因一个可选键让渠道起不来。
   *
   * <p>包级可见以便单测直接钉判据（与 {@code DiscordGatewayClient.dispatchFrameForTest} 同一种做法）。
   */
  static int resolvePollTimeoutSeconds(ChannelConfig config) {
    String raw = config.extra(EXTRA_POLL_TIMEOUT);
    if (raw == null || raw.isBlank()) {
      return DEFAULT_POLL_TIMEOUT_SECONDS;
    }
    int value;
    try {
      value = Integer.parseInt(raw.strip());
    } catch (NumberFormatException e) {
      LOG.warn(
          "{} 的 extra.{} 不是整数（{}），回落到缺省 {} 秒",
          sanitize(config.name()),
          EXTRA_POLL_TIMEOUT,
          sanitize(raw),
          DEFAULT_POLL_TIMEOUT_SECONDS);
      return DEFAULT_POLL_TIMEOUT_SECONDS;
    }
    if (value < MIN_POLL_TIMEOUT_SECONDS || value > MAX_POLL_TIMEOUT_SECONDS) {
      LOG.warn(
          "{} 的 extra.{} = {} 越界（允许 {}~{}），回落到缺省 {} 秒",
          sanitize(config.name()),
          EXTRA_POLL_TIMEOUT,
          value,
          MIN_POLL_TIMEOUT_SECONDS,
          MAX_POLL_TIMEOUT_SECONDS,
          DEFAULT_POLL_TIMEOUT_SECONDS);
      return DEFAULT_POLL_TIMEOUT_SECONDS;
    }
    return value;
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
    String probe = tokenUrl("/getMe");
    guard.check(probe);
    running = true;
    offset = 0;
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    normalizer = new TelegramEventNormalizer(config.name(), config.appSecret());
    sender = new TelegramMessageSender(guard, apiBase, config.appId());
    pollThread = Thread.ofVirtual().name("oryxos-telegram-" + config.name()).start(this::pollLoop);
    state = ChannelStatus.State.CONNECTED;
    lastError = null;
    LOG.info("Telegram 渠道 {} 长轮询已启动（Agent: {}）", sanitize(config.name()), sanitize(config.agent()));
  }

  @Override
  public synchronized void stop() {
    running = false;
    Thread t = pollThread;
    if (t != null) {
      t.interrupt();
      pollThread = null;
    }
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
    TelegramMessageSender current = sender;
    if (current == null) {
      throw new IllegalStateException("渠道 " + name() + " 尚未启动");
    }
    current.send(chatId, text, replyToMessageId);
  }

  private void pollLoop() {
    while (running) {
      try {
        JsonNode result = getUpdates();
        if (result != null && result.isArray()) {
          for (JsonNode update : result) {
            long updateId = update.path(FIELD_UPDATE_ID).asLong(0);
            if (updateId >= offset) {
              offset = updateId + 1;
            }
            Optional<InboundMessage> msg = normalizer.normalize(update);
            msg.ifPresent(this::dispatch);
          }
        }
        lastError = null;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        if (!running) {
          return;
        }
        LOG.warn(
            "Telegram 渠道 {} getUpdates 失败: {}", sanitize(config.name()), sanitize(e.getMessage()));
        lastError = sanitize(e.getMessage());
        sleepQuietly(2_000L);
      }
    }
  }

  private void dispatch(InboundMessage message) {
    InboundMessage enriched = resolveMedia(message, sender);
    inboundMessageService.onMessage(enriched, this);
  }

  /**
   * 把带 file_id 的附件落盘换成本地路径。
   *
   * <p>此前是把 file_id 换成下载地址直接交给核心 —— 而那个地址形如 {@code …/file/bot<token>/…}， token 就在 URL 里，于是它随 Agent
   * 输入进了 prompt、会话历史与 provider 请求。其余渠道都是先落盘 再交出本地路径，这里对齐。
   */
  InboundMessage resolveMedia(InboundMessage message, TelegramMessageSender current) {
    if (current == null || message.attachments().isEmpty()) {
      return message;
    }
    TelegramInboundMediaResolver resolver = mediaResolver;
    if (resolver == null) {
      resolver =
          new TelegramInboundMediaResolver(
              apiBase,
              io.oryxos.core.channel.InboundMediaRoots.forChannel(config.name(), MEDIA_DIR_PREFIX),
              config.name());
      mediaResolver = resolver;
    }
    return resolver.resolve(message, current::resolveFileUrl);
  }

  private JsonNode getUpdates() throws Exception {
    String url = tokenUrl("/getUpdates?timeout=" + pollTimeoutSeconds + "&offset=" + offset);
    guard.check(url);
    HttpRequest request =
        HttpRequest.newBuilder().uri(URI.create(url)).timeout(httpTimeout).GET().build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (Thread.interrupted()) {
      throw new InterruptedException("poll interrupted");
    }
    JsonNode root = MAPPER.readTree(response.body() == null ? "{}" : response.body());
    if (root == null || !root.path(FIELD_OK).asBoolean(false)) {
      throw new IllegalStateException("getUpdates 失败: " + sanitize(response.body()));
    }
    return root.get(FIELD_RESULT);
  }

  private static void sleepQuietly(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String trimSlash(String base) {
    String s = base.strip();
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  /**
   * Bot API 地址：token（{@code app_id}）进路径，本类所有带凭证的地址都由这里拼。
   *
   * <p>先确认拼出来的地址能被解析。token 含空白（{@code .env} / 带引号 YAML 的粘贴事故）时 {@link URI#create}
   * 抛错，而这条链路上唯一的捕获点是沙箱守卫——它的「非法 URL」文案会**回带整条地址**， 随后经 {@code ChannelStatus.error} 出现在 {@code GET
   * /api/v1/channels/status}（core-stage 默认无鉴权） 与 {@code LOG.error} 上。所以在这里先失败，并且不把 token 写进文案。
   *
   * <p>判据就是 {@link URI#create} 本身：守卫能接受的地址这里一律放行，这里拦下的都是守卫会回带的那一类， 因此不额外规定 token 的格式（本仓测试夹具用的
   * {@code app-id} 一类占位同样放行）。
   */
  private String tokenUrl(String path) {
    String url = trimSlash(apiBase) + "/bot" + config.appId() + path;
    try {
      URI.create(url);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "渠道 " + config.name() + " 的 app_id 含不能用于 URL 路径的字符（空白/换行等），请检查配置");
    }
    return url;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
