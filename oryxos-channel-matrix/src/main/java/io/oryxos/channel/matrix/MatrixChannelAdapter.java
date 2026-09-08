package io.oryxos.channel.matrix;

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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Matrix 入站：Client-Server {@code /sync} 长轮询。{@code app_id}=bot MXID，{@code app_secret}=access
 * token，{@code extra.homeserver}。
 */
public class MatrixChannelAdapter implements InboundChannelAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(MatrixChannelAdapter.class);

  public static final String TYPE = "matrix";
  private static final Duration SYNC_TIMEOUT = Duration.ofSeconds(40);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;

  private volatile MatrixEventNormalizer normalizer;
  private volatile MatrixMessageSender sender;
  private volatile HttpClient http;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;
  private volatile boolean running;
  private volatile Thread pollThread;
  private volatile String since;

  public MatrixChannelAdapter(
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
    if (config.extra("homeserver") == null || config.extra("homeserver").isBlank()) {
      throw new IllegalArgumentException("渠道 " + config.name() + " 缺少 extra.homeserver");
    }
    if (profileRegistry.get(config.agent()).isEmpty()) {
      throw new IllegalArgumentException(
          "渠道 " + config.name() + " 绑定的 Agent " + config.agent() + " 不存在");
    }
    guard.check(config.extra("homeserver"));
    running = true;
    since = null;
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    normalizer = new MatrixEventNormalizer(config.name(), config.appId());
    sender = new MatrixMessageSender(guard, config.extra("homeserver"), config.appSecret());
    pollThread = Thread.ofVirtual().name("oryxos-matrix-" + config.name()).start(this::syncLoop);
    state = ChannelStatus.State.CONNECTED;
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
    return ChannelStatus.ok(name(), TYPE, boundAgent(), state);
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    MatrixMessageSender current = sender;
    if (current == null) {
      throw new IllegalStateException("渠道 " + name() + " 尚未启动");
    }
    current.send(chatId, text);
  }

  private void syncLoop() {
    while (running) {
      try {
        JsonNode root = syncOnce();
        if (root != null) {
          since = root.path("next_batch").asText(since);
          dispatchJoin(root.path("rooms").path("join"));
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        if (!running) {
          return;
        }
        LOG.warn("Matrix 渠道 {} sync 失败: {}", sanitize(config.name()), sanitize(e.getMessage()));
        sleepQuietly(2_000L);
      }
    }
  }

  private void dispatchJoin(JsonNode join) {
    if (join == null || !join.isObject()) {
      return;
    }
    Iterator<Map.Entry<String, JsonNode>> rooms = join.fields();
    while (rooms.hasNext()) {
      Map.Entry<String, JsonNode> room = rooms.next();
      boolean direct =
          room.getValue().path("account_data").path("events").toString().contains("m.direct");
      JsonNode events = room.getValue().path("timeline").path("events");
      if (!events.isArray()) {
        continue;
      }
      for (JsonNode event : events) {
        Optional<InboundMessage> msg = normalizer.normalize(room.getKey(), event, direct);
        msg.ifPresent(m -> inboundMessageService.onMessage(m, this));
      }
    }
  }

  private JsonNode syncOnce() throws Exception {
    String homeserver = MatrixMessageSender.trimSlash(config.extra("homeserver"));
    String url = homeserver + "/_matrix/client/v3/sync?timeout=30000";
    if (since != null && !since.isBlank()) {
      url += "&since=" + URLEncoder.encode(since, StandardCharsets.UTF_8);
    }
    guard.check(url);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(SYNC_TIMEOUT)
            .header("Authorization", "Bearer " + config.appSecret())
            .GET()
            .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (Thread.interrupted()) {
      throw new InterruptedException("sync interrupted");
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException("sync HTTP " + response.statusCode());
    }
    return MAPPER.readTree(response.body() == null ? "{}" : response.body());
  }

  private static void sleepQuietly(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
