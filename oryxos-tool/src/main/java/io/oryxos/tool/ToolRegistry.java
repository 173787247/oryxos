package io.oryxos.tool;

import io.oryxos.core.OryxTool;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工具注册表：所有来源（内置 @Tool、方式三 @Tool、MCP、直接实现）统一成 {@link OryxTool} 在此汇总， ReAct 循环与 ToolExecutor
 * 由此对来源无感知（TechSol §6.6）。
 *
 * <p>两条注册路径：{@link #register}（直接实现 / MCP adapter）与 {@link #registerAnnotated} （@Tool 注解扫描——schema
 * 自动生成，宪法 II 第二件事）。重名拒绝：静默覆盖会让两个来源打架且难查。
 *
 * <p>{@link #asMap()}/{@link #mcpToolOwners()} 返回活视图，不 snapshot——管理台增删 MCP 必须立刻反映到 Prompt / 执行 /
 * {@code GET /api/v1/tools}，否则会出现「status 已连接、模型侧仍是开机那一版」。
 */
public class ToolRegistry {

  private final Map<String, OryxTool> tools = new ConcurrentHashMap<>();
  // 工具名 -> 提供它的 MCP server 名（仅 MCP 来源的工具在此有记录）；ToolExecutor 据此校验 Agent 的 mcp_servers 声明。
  private final Map<String, String> mcpToolOwners = new ConcurrentHashMap<>();

  public void register(OryxTool tool) {
    OryxTool previous = tools.putIfAbsent(tool.getName(), tool);
    if (previous != null) {
      throw new IllegalStateException("工具重名，拒绝注册: " + tool.getName());
    }
  }

  /** MCP 工具注册：额外记录"这个工具名属于哪个 server"，供运行时 mcp_servers 白名单校验。 */
  public void registerMcpTool(String serverName, OryxTool tool) {
    register(tool);
    mcpToolOwners.put(tool.getName(), serverName);
  }

  /** 注销一个工具（MCP server 断开/删除时用）；未注册的名字幂等跳过。 */
  public void unregister(String name) {
    tools.remove(name);
    mcpToolOwners.remove(name);
  }

  /** 工具名 -> 所属 MCP server 名（仅 MCP 来源的工具在内）；供 {@code ToolExecutor} 做 mcp_servers 白名单校验。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "必须返回活视图：MCP 增删后 ToolExecutor 才能看到新的归属，copyOf 会把白名单冻在启动瞬间")
  public Map<String, String> mcpToolOwners() {
    return Collections.unmodifiableMap(mcpToolOwners);
  }

  /**
   * @Tool 注解方法扫描注册：schema 由 Spring AI 自动生成，逐方法包装为 OryxTool。
   */
  public void registerAnnotated(Object bean) {
    for (ToolCallback callback : ToolCallbacks.from(bean)) {
      register(new AnnotatedToolAdapter(callback));
    }
  }

  public boolean contains(String name) {
    return tools.containsKey(name);
  }

  public Optional<OryxTool> get(String name) {
    return Optional.ofNullable(tools.get(name));
  }

  public Collection<OryxTool> all() {
    return List.copyOf(tools.values());
  }

  /** 供 OryxOsRuntime 的 tools() bean——活视图，PromptBuilder/ToolExecutor/管理台列表共用同一份。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "必须返回活视图：MCP connect/disconnect 后 ReAct 与 GET /tools 才能看到同一份注册表")
  public Map<String, OryxTool> asMap() {
    return Collections.unmodifiableMap(tools);
  }

  /** 按 Profile 的 tools 字段过滤：结果 = 声明列表中存在于注册表的项，不多不少；未知名跳过。 */
  public List<OryxTool> filterByNames(List<String> names) {
    List<OryxTool> filtered = new ArrayList<>();
    for (String name : names) {
      OryxTool tool = tools.get(name);
      if (tool != null) {
        filtered.add(tool);
      }
    }
    return filtered;
  }
}
