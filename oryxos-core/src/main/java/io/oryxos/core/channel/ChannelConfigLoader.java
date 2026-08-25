package io.oryxos.core.channel;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * 读写 {@code .oryxos/channels.yaml}（顶层 channels: 列表；文件缺失 = 零渠道，启动照常）。
 *
 * <p><b>raw 与 resolved 两套读法，不能混用</b>（与 McpConfigLoader 同口径，017 R6）：{@link #load} 解析 {@code ${ENV}}
 * 占位拿真实凭证，只给真正建连接的装配/Admin 用；{@link #loadRaw} 保留字面量，给管理台 CRUD（展示 / 改后 {@link #save}
 * 回写）用——否则改一次配置就把明文凭证写回磁盘（宪法 VI：敏感配置走环境变量，不落盘明文）。
 *
 * <p>与既有两个 loader 的差异：占位符缺失不是 WARN 放行——resolved 值仍含 {@code ${} } 会在装配/Admin 校验时点名报错、 该渠道不上线（FR-013
 * / SC-008），检测逻辑见 {@link ChannelConfig#validateCredentialsResolved()}。
 */
public class ChannelConfigLoader {

  private static final Logger LOG = LoggerFactory.getLogger(ChannelConfigLoader.class);

  private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");
  private static final String POSIX_VIEW = "posix";

  private final Path configFile;

  public ChannelConfigLoader(Path configFile) {
    this.configFile = configFile;
  }

  /** 解析占位符后的配置——唯一给真实连接用的读法，凭证只存活在内存。 */
  public List<ChannelConfig> load() {
    List<ChannelConfig> resolved = new ArrayList<>();
    for (ChannelConfig c : loadRaw()) {
      resolved.add(resolve(c));
    }
    return resolved;
  }

  /** 对单份原始配置解析占位符，供 Admin 增/改后立即建连用。 */
  public ChannelConfig resolve(ChannelConfig raw) {
    return new ChannelConfig(
        raw.name(),
        raw.type(),
        resolvePlaceholders(raw.appId()),
        resolvePlaceholders(raw.appSecret()),
        raw.agent(),
        raw.enabled());
  }

  /**
   * 不解析占位符的原始配置——管理台 CRUD 专用，保证 {@code ${VAR}} 字面量原样落盘。
   *
   * <p>结构非法（YAML 解析失败 / 条目缺字段 / name 重复）抛 {@link IllegalArgumentException} 点名报错， 不静默按零渠道处理——绑定格式非法属
   * SC-008 要求点名的三类配置错误之一。
   */
  @SuppressWarnings("unchecked")
  public List<ChannelConfig> loadRaw() {
    if (!Files.isRegularFile(configFile)) {
      return List.of();
    }
    Map<String, Object> root;
    try (Reader reader = Files.newBufferedReader(configFile)) {
      root = new Yaml().load(reader);
    } catch (IOException | RuntimeException e) {
      throw new IllegalArgumentException("channels.yaml 解析失败: " + sanitize(e.getMessage()), e);
    }
    Object channels = root == null ? null : root.get("channels");
    if (channels == null) {
      return List.of();
    }
    if (!(channels instanceof List)) {
      throw new IllegalArgumentException("channels.yaml 顶层 channels 必须是列表");
    }
    List<ChannelConfig> configs = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Object item : (List<Object>) channels) {
      if (!(item instanceof Map)) {
        throw new IllegalArgumentException(
            "channels.yaml 存在非对象条目: " + sanitize(String.valueOf(item)));
      }
      Map<String, Object> entry = (Map<String, Object>) item;
      ChannelConfig config =
          new ChannelConfig(
              asString(entry.get("name")),
              asString(entry.get("type")),
              asString(entry.get("app_id")),
              asString(entry.get("app_secret")),
              asString(entry.get("agent")),
              entry.get("enabled") == null
                  || Boolean.parseBoolean(String.valueOf(entry.get("enabled"))));
      config.validateShape();
      if (!seen.add(config.name())) {
        throw new IllegalArgumentException("channels.yaml 渠道名重复: " + config.name());
      }
      configs.add(config);
    }
    return configs;
  }

  /** 整份列表覆写回 channels.yaml；传入的必须是 {@link #loadRaw} 口径的未解析配置。落盘后权限收紧 rw-------。 */
  public void save(List<ChannelConfig> configs) {
    List<Map<String, Object>> channels = new ArrayList<>();
    for (ChannelConfig c : configs) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("name", c.name());
      entry.put("type", c.type());
      entry.put("app_id", c.appId());
      entry.put("app_secret", c.appSecret());
      entry.put("agent", c.agent());
      entry.put("enabled", c.enabled());
      channels.add(entry);
    }
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("channels", channels);
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    String yaml = new Yaml(options).dump(root);
    try {
      Path parent = configFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(configFile, yaml);
      restrictToOwner(configFile); // 占位符解析不了时文件里可能出现真实凭证——只给属主可读写
    } catch (IOException e) {
      throw new UncheckedIOException("写入 channels.yaml 失败", e);
    }
  }

  private static void restrictToOwner(Path path) throws IOException {
    if (!path.getFileSystem().supportedFileAttributeViews().contains(POSIX_VIEW)) {
      return;
    }
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
  }

  private String resolvePlaceholders(String text) {
    if (text == null) {
      return null;
    }
    Matcher matcher = ENV_PLACEHOLDER.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      String value = System.getenv(matcher.group(1));
      if (value == null) {
        // 保留字面量：由 ChannelConfig.validateCredentialsResolved 在上线前点名拒绝，而非静默放行
        LOG.warn("环境变量未设置，占位符保留原样: {}", sanitize(matcher.group(1)));
        value = matcher.group(0);
      }
      matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  private static String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
