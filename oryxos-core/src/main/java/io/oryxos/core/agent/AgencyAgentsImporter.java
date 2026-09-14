package io.oryxos.core.agent;

import io.oryxos.core.agent.AgencyAgentsParser.ParsedExpert;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.DumperOptions.FlowStyle;
import org.yaml.snakeyaml.Yaml;

/**
 * agency-agents-zh → OryxOS AGENT.md 组装器（025）：{@link ParsedExpert} + defaultProvider +
 * availableTools → 一段可落盘的 AGENT.md markdown。纯 POJO、零 Spring、无 IO。frontmatter 用 SnakeYAML BLOCK
 * dump（镜像 {@link AgentLifecycleService#assembleMarkdown} 的写法）。
 */
public final class AgencyAgentsImporter {

  /** 导入器内置的「默认安全工具集」——源文件工具无关（frontmatter 无 tools），落地时给专家一套可跑的安全能力。 */
  private static final List<String> DEFAULT_TOOLS =
      List.of("read_file", "shell", "notify", "web_search", "http_get", "fetch_webpage");

  /**
   * 3 参便捷重载：name 从 displayName 派生（slug 化）、model 落占位，与 5 参 {@link #toMarkdown} 行为一致。缺 name 时
   * identity.agent_name / persona.name 仍保留展示名（中文 OK），仅 frontmatter {@code name:} 被 slug
   * 化——目录名、profile 名、 文件浏览三处一致，避免中文展示名进文件系统段。
   */
  public String toMarkdown(ParsedExpert e, String defaultProvider, Set<String> availableTools) {
    return toMarkdown(e, defaultProvider, availableTools, slugify(e.displayName()), null);
  }

  /**
   * 5 参主重载：frontmatter {@code name:} 用显式传入的 slug（与目录名/profile 名一致），identity.agent_name 与
   * persona.name 保留中文展示名；provider.model 用显式传入的 model（空则落「请在此填写模型名」占位，与 create 脚手架同语义）。 调用方（web 导入 /
   * CLI）必须把已解析的合法 name 传进来，否则中文展示名会进 profile 名，后续文件系统操作 （skillBindings.inspect 等）按安全段名校验直接 400。
   */
  public String toMarkdown(
      ParsedExpert e,
      String defaultProvider,
      Set<String> availableTools,
      String name,
      String model) {
    Map<String, Object> fm = new LinkedHashMap<>();
    fm.put("name", blankTo(name, e.displayName()));
    fm.put("description", blankTo(e.description(), "描述这个 Agent 做什么"));

    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("agent_name", e.displayName());
    identity.put("prompt", identityPrompt(e)); // 角色定位 + 记忆/经验（背景知识）
    fm.put("identity", identity);

    fm.put("persona", persona(e));

    Map<String, Object> provider = new LinkedHashMap<>();
    provider.put("name", blankTo(defaultProvider, "deepseek"));
    provider.put("model", blankTo(model, "请在此填写模型名")); // 与 create 脚手架同语义
    fm.put("provider", provider);

    fm.put("tools", intersectTools(availableTools)); // 源声明 ∩ 本机可用（幂等，未知 tool 静默剔除）
    fm.put("channels", List.of("cli"));

    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("max_iterations", 10);
    settings.put("max_history_turns", 20);
    fm.put("settings", settings);

    return assembleMarkdown(fm, e.body());
  }

  /** persona 段：七字段结构全填；role 为空兜底「乐于助人的助手」；可空字段非空才写。 */
  private static Map<String, Object> persona(ParsedExpert e) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("name", blankTo(e.displayName(), "助手"));
    p.put("role", blankTo(e.role(), "乐于助人的助手")); // 默认人格的 role
    putIfNonBlank(p, "traits", e.traits());
    putIfNonBlank(p, "tone", e.communication());
    putIfNonBlank(p, "values", e.keyRules()); // 关键规则（正向） → 行为准则
    putIfNonBlank(p, "boundaries", e.boundaries()); // 否定式红线 → 边界（025映射表）
    putIfNonBlank(p, "sample_style", e.sampleStyle()); // 评论/回复格式段 → 风格示范（契约四锚点）
    return p;
  }

  /** 源声明 ∩ 本机可用：默认安全集里本机没有的 tool 静默剔除（幂等——同一份源反复导入结果一致）。 */
  private static List<String> intersectTools(Set<String> availableTools) {
    if (availableTools == null) {
      return List.of();
    }
    return DEFAULT_TOOLS.stream().filter(availableTools::contains).toList();
  }

  /** identity.prompt：角色定位打头 + 记忆/经验背景（「这个 Agent 知道什么」，不是 persona 的行为字段）。 */
  private static String identityPrompt(ParsedExpert e) {
    StringBuilder sb = new StringBuilder();
    if (e.role() != null && !e.role().isBlank()) {
      sb.append("你是").append(e.displayName()).append("，").append(e.role()).append("。\n");
    }
    if (e.background() != null && !e.background().isBlank()) {
      sb.append("背景知识与经验：\n").append(e.background());
    }
    return sb.toString().strip();
  }

  /** 镜像 {@link AgentLifecycleService#assembleMarkdown}：BLOCK dump + 围栏约定。 */
  private static String assembleMarkdown(Map<String, Object> frontmatter, String body) {
    DumperOptions opts = new DumperOptions();
    opts.setDefaultFlowStyle(FlowStyle.BLOCK);
    String yaml = new Yaml(opts).dump(frontmatter);
    return "---\n" + yaml + "---\n\n" + body + "\n";
  }

  private static String blankTo(String v, String fallback) {
    return v == null || v.isBlank() ? fallback : v;
  }

  /** 与 Web 导入端点的 {@code resolveImportName} 同一 slug 规则：[A-Za-z0-9_-]+，其余剥离。 */
  private static String slugify(String displayName) {
    return displayName == null ? "" : displayName.replaceAll("[^A-Za-z0-9_-]", "");
  }

  private static void putIfNonBlank(Map<String, Object> map, String key, String value) {
    if (value != null && !value.isBlank()) {
      map.put(key, value);
    }
  }
}
