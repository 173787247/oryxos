package io.oryxos.core.policy;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** 内置 / MCP 工具 → {@link HighRiskActionType} 分类器（042 / #464）。规则可直接按动作类型配置，无需枚举每个工具名。 */
public final class HighRiskActionClassifier {

  private static final Set<String> SHELL = Set.of("shell");

  private static final Set<String> EXTERNAL_SEND =
      Set.of("http_post", "http_request", "notify", "fetch_webpage", "web_search");

  private static final Set<String> FILE_MUTATION =
      Set.of(
          "write_file",
          "edit_file",
          "append_file",
          "delete_file",
          "move_file",
          "copy_file",
          "make_dir");

  private final Function<String, String> mcpOwnerLookup;

  public HighRiskActionClassifier() {
    this(name -> null);
  }

  /**
   * @param mcpOwnerLookup 工具名 → MCP server 名；非 MCP 返回 null
   */
  public HighRiskActionClassifier(Function<String, String> mcpOwnerLookup) {
    this.mcpOwnerLookup = mcpOwnerLookup == null ? name -> null : mcpOwnerLookup;
  }

  public Optional<HighRiskActionType> classify(String toolName) {
    if (toolName == null || toolName.isBlank()) {
      return Optional.empty();
    }
    String name = toolName.trim();
    String lower = name.toLowerCase(Locale.ROOT);
    if (SHELL.contains(lower)) {
      return Optional.of(HighRiskActionType.SHELL);
    }
    if (EXTERNAL_SEND.contains(lower)) {
      return Optional.of(HighRiskActionType.EXTERNAL_SEND);
    }
    if (FILE_MUTATION.contains(lower)) {
      return Optional.of(HighRiskActionType.FILE_MUTATION);
    }
    if (mcpOwnerLookup.apply(name) != null) {
      return Optional.of(HighRiskActionType.MCP);
    }
    return Optional.empty();
  }
}
