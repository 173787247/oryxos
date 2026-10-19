package io.oryxos.core.policy;

import io.oryxos.core.io.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * 治理元数据读取（041 / #463）。
 *
 * <p>Agent / Skill / Knowledge 约定落在 {@code
 * .oryxos/{agents|skills|knowledge}/&lt;name&gt;/GOVERNANCE.yml}。 渠道不写侧车：从同一工作区根的 {@code
 * channels.yaml} 条目 {@code governance:} 块读取。写入渠道治理只走渠道 Admin → loader.save，本类不另写一份以免被覆盖丢掉。
 *
 * <p>缺文件或未知渠道返回 {@link AssetGovernance#empty()}——调用方不得把「没元数据」当成拒绝。侧车写入用原子改名。
 *
 * <p>读不出来（超限 / 非法 YAML / IO 失败）仍是 fail-open：按未设治理继续运行，调用方的门禁随之失效。但这是**安全控制失效**， 不是噪音，所以按 ERROR
 * 记，并带上资产身份——否则排障时定位不到是哪个资产的门禁悄悄关掉了。这类加载点会被授权判定与入站消息 反复调用，同一份坏文件每个进程只报一次（见 {@link
 * #reportUnreadable}）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "CRLF_INJECTION_LOGS",
    justification = "日志里的资产标识 / 文件路径 / 原因已经 sanitize() 消去 CR/LF；taint 分析不跨方法追踪该消毒，故局部抑制")
public final class AssetGovernanceStore {

  private static final Logger LOG = LoggerFactory.getLogger(AssetGovernanceStore.class);

  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]+");

  /** 侧车文件名（常量：避免调用点散落字面量）。 */
  static final String FILE_NAME = "GOVERNANCE.yml";

  /** 治理侧车 / channels.yaml 读上限（与其它工作区文本 10 MiB 对齐）；超限按未设治理处理。 */
  static final long MAX_GOVERNANCE_FILE_BYTES = 10L * 1024 * 1024;

  /**
   * 已报告过的「读不出来」目标（throttle key → 原因）。
   *
   * <p>为什么需要：{@code loadAgent}/{@code loadChannel} 会被入站消息门禁（每条消息）与授权判定（每个请求）反复调用， 逐次打 ERROR
   * 就是刷屏。同一份坏文件每个进程只报一次；读到它恢复正常时清标记，再次坏掉会重新报。
   *
   * <p>key 粒度：侧车与 channels.yaml 都用**文件级** key——坏的是文件，不是某一个资产。代价是 channels.yaml 坏掉时，
   * 日志里只会出现**第一个**撞上它的渠道名，其余渠道共享同一条报告；这是有意为之，改成 channel 级 key 会放大成 N 条。
   */
  private static final Map<String, String> REPORTED_UNREADABLE = new ConcurrentHashMap<>();

  private static final String DIR_AGENTS = "agents";

  private static final String DIR_SKILLS = "skills";

  private static final String DIR_KNOWLEDGE = "knowledge";

  private static final String KEY_OWNER = "owner";

  private static final String KEY_VERSION = "version";

  private static final String KEY_VISIBILITY = "visibility";

  private static final String KEY_RISK = "riskLevel";

  private static final String KEY_HEALTH = "health";

  private static final String KEY_TEAM_OWNER = "teamOwner";

  private static final String KEY_ORG_OWNER = "orgOwner";

  /** 渠道配置文件名（与运行时 {@code oryxosRoot/channels.yaml} 同一路径）。 */
  static final String CHANNELS_FILE = "channels.yaml";

  private static final String KEY_CHANNELS = "channels";

  private static final String KEY_ENTRY_NAME = "name";

  /** channels.yaml 条目上的治理块键。Loader 回写必须使用同一常量，避免两套字面量漂移。 */
  public static final String KEY_GOVERNANCE = "governance";

  private final Path workspaceRoot;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Path 是不可变路径值；规范化后只存根目录引用供后续 resolve。")
  public AssetGovernanceStore(Path workspaceRoot) {
    if (workspaceRoot == null) {
      throw new IllegalArgumentException("workspaceRoot 不能为空");
    }
    this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
  }

  /** 读取 Agent 侧车；缺文件或解析失败返回 empty（解析失败按 ERROR 记资产标识，不把脏文件当成拒绝）。 */
  public AssetGovernance loadAgent(String name) {
    return load(path(DIR_AGENTS, name), "agent:" + name);
  }

  /** 读取 Skill 侧车。 */
  public AssetGovernance loadSkill(String name) {
    return load(path(DIR_SKILLS, name), "skill:" + name);
  }

  /** 读取知识库侧车。 */
  public AssetGovernance loadKnowledge(String name) {
    return load(path(DIR_KNOWLEDGE, name), "knowledge:" + name);
  }

  /** 按资源类型加载；未知类型返回 empty。 */
  public AssetGovernance load(String resourceType, String id) {
    if (resourceType == null) {
      return AssetGovernance.empty();
    }
    return switch (resourceType) {
      case ResourceRef.TYPE_AGENT -> loadAgent(id);
      case ResourceRef.TYPE_SKILL -> loadSkill(id);
      case ResourceRef.TYPE_KNOWLEDGE -> loadKnowledge(id);
      case ResourceRef.TYPE_CHANNEL -> loadChannel(id);
      default -> AssetGovernance.empty();
    };
  }

  /** 写入 Agent 侧车。 */
  public void saveAgent(String name, AssetGovernance governance) {
    save(path(DIR_AGENTS, name), governance);
  }

  /** 写入 Skill 侧车。 */
  public void saveSkill(String name, AssetGovernance governance) {
    save(path(DIR_SKILLS, name), governance);
  }

  /** 写入知识库侧车。 */
  public void saveKnowledge(String name, AssetGovernance governance) {
    save(path(DIR_KNOWLEDGE, name), governance);
  }

  /** 读取渠道治理块。缺文件、未知名称、块缺失或解析失败均返回 empty（不把脏文件当成拒绝，也不记录可能含凭证的原文）。 */
  public AssetGovernance loadChannel(String name) {
    if (name == null || !SAFE_NAME.matcher(name).matches()) {
      return AssetGovernance.empty();
    }
    Path file = workspaceRoot.resolve(CHANNELS_FILE);
    if (!Files.isRegularFile(file)) {
      return AssetGovernance.empty();
    }
    String throttleKey = throttleKey(file);
    try {
      if (Files.size(file) > MAX_GOVERNANCE_FILE_BYTES) {
        reportUnreadable(throttleKey, "channel:" + name, file.toString(), "channels.yaml 过大", null);
        return AssetGovernance.empty();
      }
      String yaml = Files.readString(file);
      if (yaml.isBlank()) {
        markReadable(throttleKey);
        return AssetGovernance.empty();
      }
      Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
      markReadable(throttleKey); // 文件本身能解析：之前的「读不出来」已恢复（结构不符不算读失败）
      if (!(loaded instanceof Map<?, ?> root)) {
        return AssetGovernance.empty();
      }
      Object channels = root.get(KEY_CHANNELS);
      if (!(channels instanceof List<?> list)) {
        return AssetGovernance.empty();
      }
      for (Object item : list) {
        if (!(item instanceof Map<?, ?> entry)) {
          continue;
        }
        if (name.equals(text(entry.get(KEY_ENTRY_NAME)))) {
          return parseNode(entry.get(KEY_GOVERNANCE));
        }
      }
      return AssetGovernance.empty();
    } catch (IOException | YAMLException ex) {
      reportUnreadable(
          throttleKey, "channel:" + name, file.toString(), "读取 channels.yaml 治理块失败", ex);
      return AssetGovernance.empty();
    }
  }

  private Path path(String dir, String name) {
    requireSafe(name);
    return workspaceRoot.resolve(dir).resolve(name).resolve(FILE_NAME);
  }

  private static void requireSafe(String name) {
    if (name == null || !SAFE_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("非法资产名: " + name);
    }
  }

  private AssetGovernance load(Path file, String assetRef) {
    if (!Files.isRegularFile(file)) {
      return AssetGovernance.empty();
    }
    String throttleKey = throttleKey(file);
    try {
      if (Files.size(file) > MAX_GOVERNANCE_FILE_BYTES) {
        reportUnreadable(throttleKey, assetRef, file.toString(), "GOVERNANCE.yml 过大", null);
        return AssetGovernance.empty();
      }
      String text = Files.readString(file);
      if (text.isBlank()) {
        markReadable(throttleKey);
        return AssetGovernance.empty();
      }
      Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
      if (!(loaded instanceof Map<?, ?> raw)) {
        reportUnreadable(throttleKey, assetRef, file.toString(), "GOVERNANCE.yml 不是映射", null);
        return AssetGovernance.empty();
      }
      markReadable(throttleKey); // 真正解析出治理块才算恢复（「不是映射」仍算读不出来）
      return fromMap(raw);
    } catch (IOException | YAMLException ex) {
      reportUnreadable(throttleKey, assetRef, file.toString(), "读取 GOVERNANCE.yml 失败", ex);
      return AssetGovernance.empty();
    }
  }

  /** 解析嵌套治理块。非映射、空映射或无有效字段返回 empty。未知键忽略——避免把凭证字段带进治理模型。 */
  public static AssetGovernance parseNode(Object node) {
    if (!(node instanceof Map<?, ?> raw) || raw.isEmpty()) {
      return AssetGovernance.empty();
    }
    return fromMap(raw);
  }

  /** 可回写的治理字段。未设返回空映射（调用方省略键）。只含已知字段，不含凭证。 */
  public static Map<String, String> toBlock(AssetGovernance governance) {
    Map<String, String> body = new LinkedHashMap<>();
    if (governance == null || !governance.isPresent()) {
      return body;
    }
    putText(body, KEY_OWNER, governance.owner());
    putText(body, KEY_VERSION, governance.version());
    if (governance.visibility() != null) {
      body.put(KEY_VISIBILITY, governance.visibility().name());
    }
    putText(body, KEY_RISK, governance.riskLevel());
    if (governance.health() != null) {
      body.put(KEY_HEALTH, governance.health().name());
    }
    putText(body, KEY_TEAM_OWNER, governance.teamOwner());
    putText(body, KEY_ORG_OWNER, governance.orgOwner());
    return body;
  }

  private static void putText(Map<String, String> body, String key, String value) {
    if (value != null && !value.isBlank()) {
      body.put(key, value.strip());
    }
  }

  private static AssetGovernance fromMap(Map<?, ?> raw) {
    return new AssetGovernance(
        text(raw.get(KEY_OWNER)),
        text(raw.get(KEY_VERSION)),
        AssetGovernance.parseVisibility(text(raw.get(KEY_VISIBILITY))),
        text(raw.get(KEY_RISK)),
        AssetGovernance.parseHealth(text(raw.get(KEY_HEALTH))),
        text(raw.get(KEY_TEAM_OWNER)),
        text(raw.get(KEY_ORG_OWNER)));
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String rendered = value.toString().strip();
    return rendered.isEmpty() ? null : rendered;
  }

  private void save(Path file, AssetGovernance governance) {
    if (governance == null) {
      throw new IllegalArgumentException("governance 不能为空");
    }
    AtomicFiles.writeString(file, render(governance));
  }

  /** 渲染侧车 YAML（块风格，字段名稳定）。与 channels.yaml 治理块共用 {@link #toBlock}。公开供 #537 版本快照落库。 */
  public static String snapshotYaml(AssetGovernance governance) {
    return render(governance == null ? AssetGovernance.empty() : governance);
  }

  /**
   * 解析 #537 快照 YAML 回模型（#541 restore）。脏/空文本回落 empty，不因脏快照炸请求。
   *
   * @param assetRef 快照所属资产（如 {@code agent:ops-skill revision=7}）：解析失败时进 ERROR 日志，排障要知道是哪个资产
   */
  public static AssetGovernance parseSnapshotYaml(String text, String assetRef) {
    if (text == null || text.isBlank()) {
      return AssetGovernance.empty();
    }
    String throttleKey =
        "snapshot:" + (assetRef == null || assetRef.isBlank() ? "<unknown>" : assetRef);
    try {
      Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
      markReadable(throttleKey);
      return parseNode(loaded);
    } catch (YAMLException ex) {
      reportUnreadable(throttleKey, assetRef, "治理版本快照", "解析治理快照 YAML 失败", ex);
      return AssetGovernance.empty();
    }
  }

  /** 渲染侧车 YAML（块风格，字段名稳定）。与 channels.yaml 治理块共用 {@link #toBlock}。 */
  static String render(AssetGovernance governance) {
    Map<String, String> body = toBlock(governance);
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setPrettyFlow(true);
    String dumped = new Yaml(options).dump(body);
    return dumped.endsWith("\n") ? dumped : dumped + "\n";
  }

  /** 变更摘要（进审计，不含凭据）。 */
  public static String summarize(AssetGovernance governance) {
    if (governance == null || !governance.isPresent()) {
      return "clear";
    }
    String visibility =
        governance.visibility() == null
            ? ""
            : governance.visibility().name().toLowerCase(Locale.ROOT);
    String health =
        governance.health() == null ? "" : governance.health().name().toLowerCase(Locale.ROOT);
    return "owner="
        + nullToEmpty(governance.owner())
        + " visibility="
        + visibility
        + " health="
        + health
        + " team="
        + nullToEmpty(governance.teamOwner())
        + " org="
        + nullToEmpty(governance.orgOwner());
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value.strip();
  }

  /** 节流 key：文件级（见 {@link #REPORTED_UNREADABLE}）。 */
  private static String throttleKey(Path file) {
    return "file:" + file;
  }

  /**
   * 治理读不出来：按 ERROR 记**一次**，带资产标识与文件，说明这是 fail-open 而不是拒启。
   *
   * <p>只打异常类名、不打异常 message：{@code YAMLException} 的 message 会带源文件片段，而 {@code channels.yaml}
   * 里有渠道凭证——本类从不在日志里回显文件内容。
   *
   * <p>同一 throttle key 命中过就不再打；{@link #markReadable} 在读到该文件恢复正常时清标记。
   */
  private static void reportUnreadable(
      String throttleKey, String assetRef, String location, String reason, Throwable cause) {
    if (REPORTED_UNREADABLE.putIfAbsent(throttleKey, reason) != null) {
      return;
    }
    LOG.error(
        "资产治理读取失败（fail-open：该资产的治理门禁按未设治理继续运行，不是拒启）：" + "asset={} location={} reason={} cause={}",
        sanitize(assetRef),
        sanitize(location),
        sanitize(reason),
        cause == null ? "-" : cause.getClass().getSimpleName());
  }

  /** 读到该文件恢复正常：清掉节流标记，之后再坏会重新报。 */
  private static void markReadable(String throttleKey) {
    REPORTED_UNREADABLE.remove(throttleKey);
  }

  /** 包级可见：仅供测试隔离静态节流表，生产路径不得调用。 */
  static void clearReportedForTest() {
    REPORTED_UNREADABLE.clear();
  }

  private static String sanitize(String value) {
    if (value == null || value.isBlank()) {
      return "<unknown>";
    }
    return value.replace('\r', '_').replace('\n', '_');
  }
}
