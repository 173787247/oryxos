package io.oryxos.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.core.agent.ProfileContext;
import io.oryxos.core.notify.NotifyChannelDef;
import io.oryxos.core.notify.NotifyChannelRegistry;
import io.oryxos.core.profile.Profile;
import io.oryxos.tool.notify.NotifyChannelAdapter;
import io.oryxos.tool.notify.NotifyTarget;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 内置工具 notify：把一条消息推送到通知渠道。
 *
 * <p>31 节起优先按<strong>全局注册表渠道名</strong>解析；缺省 / {@code default} 取注册表第一个。仍兼容 Profile 内联 {@code
 * notify_channels}（按 type 匹配）的老模型。
 */
public class NotifyTools implements OryxTool {

  /** channel 参数的"用默认渠道"字面量（课件示例用词）。 */
  private static final String DEFAULT_CHANNEL = "default";

  /** config 里存 HTTP 类渠道 webhook 地址的键（email 渠道无 url，走 SMTP）。 */
  private static final String KEY_URL = "url";

  /** 渠道类型字面量 email（走 SMTP，而非 HTTP webhook）。 */
  private static final String TYPE_EMAIL = "email";

  /** email 渠道 config 里收件人地址的键（描述里露出，模型才知道发给谁）。 */
  private static final String KEY_TO = "to";

  /** channelType → 实现（webhook/wecom/feishu/dingtalk…）；多档并存按 type 路由（课件 6.4 路一）。 */
  private final Map<String, NotifyChannelAdapter> adapters;

  /** 推送前过 HTTP 域名白名单（宪法 VI）——与 http_post 共享同一份 http.allowed_domains（24 节接线）。 */
  private final Sandbox sandbox;

  /** 全局通知渠道注册表（31 节）：按名解析成 {type,url}。 */
  private final NotifyChannelRegistry channelRegistry;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "channelRegistry 是 Spring 注入的共享单例，构造注入共享同一引用正是意图（与 adapters/sandbox 同）")
  public NotifyTools(
      Map<String, NotifyChannelAdapter> adapters,
      Sandbox sandbox,
      NotifyChannelRegistry channelRegistry) {
    this.adapters = Map.copyOf(adapters);
    this.sandbox = sandbox;
    this.channelRegistry = channelRegistry;
  }

  @Override
  public String getName() {
    return "notify";
  }

  @Override
  public String getDescription() {
    // 动态列出当前已注册的渠道名 + 类型，模型据此选 channel 并判断是 email / webhook（31 节：出口全局管理、按名引用）。
    // email 渠道额外露出收件人（config.to），否则模型只看到"test"会误判"非邮件"。
    List<NotifyChannelDef> registered = channelRegistry.list();
    if (registered.isEmpty()) {
      return "把一条消息推送到指定通知渠道。channel 传渠道名；当前无已注册渠道——去管理台「Notify 渠道」里新建。";
    }
    String names = registered.stream().map(NotifyTools::describe).collect(Collectors.joining(", "));
    return "把一条消息推送到指定通知渠道。channel 传渠道名，当前可用：" + names;
  }

  /** 渠道名(type)；email 再加 →收件人，让模型一眼看出这是发往哪个邮箱的 SMTP 渠道。 */
  private static String describe(NotifyChannelDef def) {
    String base = def.name() + "(" + def.type() + ")";
    if (TYPE_EMAIL.equals(def.type())) {
      String to = def.config().get(KEY_TO);
      if (to != null && !to.isBlank()) {
        return def.name() + "(" + def.type() + "→" + to + ")";
      }
    }
    return base;
  }

  @Override
  public String getInputSchema() {
    return """
        {
          "type": "object",
          "properties": {
            "content": {"type": "string", "description": "要推送的内容"},
            "channel": {"type": "string", "description": "渠道名（全局 Notify 注册表中的 name）；缺省或 default 用注册表第一个渠道"},
            "format": {"type": "string", "description": "消息格式：text（默认）；企微 markdown；钉钉 markdown/actionCard；飞书 post/markdown/interactive/card；未知值由对应 Adapter 拒绝"}
          },
          "required": ["content"]
        }""";
  }

  @Override
  public ToolResult execute(JsonNode input) {
    JsonNode contentNode = input.get("content");
    if (contentNode == null || contentNode.asText().isEmpty()) {
      return ToolResult.error("notify 缺少必填参数 content", false);
    }
    String content = contentNode.asText();
    String channel = input.hasNonNull("channel") ? input.get("channel").asText() : null;
    String format = input.hasNonNull("format") ? input.get("format").asText() : null;
    boolean useDefault = channel == null || channel.isBlank() || DEFAULT_CHANNEL.equals(channel);

    // 新模型（31 节）：注册表优先——缺省取第一个；显式名则 find
    if (useDefault) {
      List<NotifyChannelDef> registered = channelRegistry.list();
      if (!registered.isEmpty()) {
        return sendRegistered(registered.get(0), content, format);
      }
    } else {
      Optional<NotifyChannelDef> registered = channelRegistry.find(channel);
      if (registered.isPresent()) {
        return sendRegistered(registered.get(), content, format);
      }
      // 名字不在注册表 → 落到下面的兼容路径（按 type 匹配 Agent 内联渠道）
    }

    // 兼容老模型：从当前 Profile 的内联 notify_channels 解析
    Profile profile = ProfileContext.current();
    if (profile == null) {
      return ToolResult.error("当前无已注册通知渠道，且无 Agent 上下文可解析内联 notify_channels", false);
    }
    List<Profile.NotifyChannel> channels = profile.notifyChannels();
    if (channels.isEmpty()) {
      return ToolResult.error(
          "无已注册通知渠道，且 Profile "
              + profile.name()
              + " 未配置 notify_channels——去管理台「Notify 渠道」新建，或配置内联渠道",
          false);
    }
    Profile.NotifyChannel resolved = resolveChannel(channels, channel);
    if (resolved == null) {
      return ToolResult.error("notify_channels 中不存在类型为 " + channel + " 的渠道（不回退默认，避免消息发错地方）", false);
    }
    NotifyChannelAdapter adapter = adapters.get(resolved.type());
    if (adapter == null) {
      return ToolResult.error(
          "渠道类型 " + resolved.type() + " 没有对应实现（已装配: " + adapters.keySet() + "）", false);
    }
    NotifyTarget target = new NotifyTarget(resolved.type(), withFormat(resolved.config(), format));
    // 内联渠道有 url 才做 HTTP 预检；无 url 的渠道（如 email 走 SMTP）由适配器在 send 首行自行过沙箱
    String url = resolved.config().get(KEY_URL);
    if (url != null && !url.isBlank()) {
      sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url));
    }
    adapter.send(target, content);
    return ToolResult.ok("已推送");
  }

  private ToolResult sendRegistered(NotifyChannelDef def, String content, String format) {
    NotifyChannelAdapter adapter = adapters.get(def.type());
    if (adapter == null) {
      return ToolResult.error(
          "渠道 " + def.name() + " 的类型 " + def.type() + " 没有对应实现（已装配: " + adapters.keySet() + "）",
          false);
    }
    // 全量 config：email 用多字段、HTTP 类靠 url 回填（兼容旧行无 config 列）；与内联路径同策略。
    // （def.config() 经 NotifyChannelDef 紧凑构造器固化非空，无需判空）
    Map<String, String> config = new HashMap<>(def.config());
    if (!config.containsKey(KEY_URL) && def.url() != null && !def.url().isBlank()) {
      config.put(KEY_URL, def.url());
    }
    config = withFormat(config, format);
    String url = config.get(KEY_URL);
    if (url != null && !url.isBlank()) {
      sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url)); // email 无 url，SMTP 由适配器自检
    }
    adapter.send(new NotifyTarget(def.type(), config), content);
    return ToolResult.ok("已推送");
  }

  /** 把可选 format 写入渠道 config（不覆盖已有 format 键，除非调用方显式传入非空）。 */
  private static Map<String, String> withFormat(Map<String, String> base, String format) {
    if (format == null || format.isBlank()) {
      return base;
    }
    java.util.HashMap<String, String> merged = new java.util.HashMap<>(base);
    merged.put("format", format.strip());
    return Map.copyOf(merged);
  }

  /** channel 空白或 "default" → 第一个渠道；否则按 NotifyChannel.type 匹配（clarify 1）。 */
  private static Profile.NotifyChannel resolveChannel(
      List<Profile.NotifyChannel> channels, String channel) {
    if (channel == null || channel.isBlank() || DEFAULT_CHANNEL.equals(channel)) {
      return channels.get(0);
    }
    for (Profile.NotifyChannel candidate : channels) {
      if (channel.equals(candidate.type())) {
        return candidate;
      }
    }
    return null;
  }
}
