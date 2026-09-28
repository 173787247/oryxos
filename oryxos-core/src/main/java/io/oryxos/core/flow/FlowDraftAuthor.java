package io.oryxos.core.flow;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Direction C：自然语言 → Flow Markdown 草稿（复用「一句话生成」思路）。一次 LLM 调用产出文档 → {@link
 * FlowDocuments#parseAndValidate} 校验；非法 → {@link IllegalArgumentException}。不落盘。
 */
public final class FlowDraftAuthor {

  private static final Pattern FENCE =
      Pattern.compile("^```(?:ya?ml|markdown|md)?\\s*\\n(.*)\\n```\\s*$", Pattern.DOTALL);
  private static final Pattern ID_LINE = Pattern.compile("(?m)^id:\\s*[\"']?[^\\n\"']+[\"']?\\s*$");

  private static final String PROMPT =
      """
      你是 OryxOS 的 Flow 作者。根据末尾「需求」产出一份可静态校验通过的 Flow Markdown（045 DSL）。

      硬性规则：
      1. 整份输出必须是一份 Flow 文档：YAML frontmatter 以 --- 开头并以 --- 结束，后接简短 Markdown 正文说明。
      2. frontmatter 必须含：apiVersion: oryxos.flow/v1、kind: Flow、id、version、entry、nodes、edges。
      3. id 必须精确等于「{id}」；entry 必须是 nodes 里存在的节点 id。
      4. 节点 type 只能是：agent / tool / notify / human / approval / team_task（可用 team-task 别名）。
      5. agent / team_task 的 ref 只能从下面【可用 Agent】里选；没有合适的可省略 ref 或只用 notify/human。
      6. ports：inputs/outputs 用 name → {type, required?, from?}；from 形如 otherNode.port。
      7. edges: 列表项 {from, to, when?}；when 可省略。
      8. 不要编造清单外的 Agent 名；不要用 Markdown 代码围栏包住整份输出；不要解释。

      【可用 Agent】
      {agents}

      【正确示例（id 以上面规则为准）】
      ---
      apiVersion: oryxos.flow/v1
      kind: Flow
      id: hello-notify
      version: "1.0.0"
      entry: draft
      nodes:
        draft:
          type: agent
          ref: writer
          inputs:
            topic:
              type: string
              required: true
          outputs:
            message:
              type: string
        send:
          type: notify
          inputs:
            text:
              type: string
              from: draft.message
              required: true
          outputs:
            delivered:
              type: boolean
      edges:
        - from: draft
          to: send
      ---

      Minimal linear Flow.

      需求：
      """;

  private final Function<String, String> llm;
  private final Supplier<List<String>> agentNames;

  public FlowDraftAuthor(Function<String, String> llm, Supplier<List<String>> agentNames) {
    this.llm = Objects.requireNonNull(llm, "llm");
    this.agentNames = agentNames == null ? List::of : agentNames;
  }

  public record Draft(String flowId, String markdown, FlowDefinition definition) {
    public Draft {
      flowId = Objects.requireNonNull(flowId, "flowId");
      markdown = Objects.requireNonNull(markdown, "markdown");
      definition = Objects.requireNonNull(definition, "definition");
    }
  }

  /** Prefer explicit id; otherwise slugify goal. */
  public Draft generate(String flowIdOrNull, String goal) {
    if (goal == null || goal.isBlank()) {
      throw new IllegalArgumentException("goal is required");
    }
    String id =
        flowIdOrNull == null || flowIdOrNull.isBlank() ? slugify(goal) : slugify(flowIdOrNull);
    if (id.isBlank()) {
      throw new IllegalArgumentException("flow id is empty after slugify");
    }
    List<String> agents = agentNames.get();
    if (agents == null) {
      agents = List.of();
    }
    String agentBlock =
        agents.isEmpty()
            ? "（当前没有已注册 Agent；优先用 notify / human / tool 节点，agent.ref 可省略）"
            : agents.stream().map(a -> "- " + a).collect(Collectors.joining("\n"));
    String prompt = PROMPT.replace("{id}", id).replace("{agents}", agentBlock) + goal.strip();
    String raw = llm.apply(prompt);
    if (raw == null || raw.isBlank()) {
      throw new IllegalStateException("模型未返回 Flow 草稿");
    }
    String markdown = normalizeMarkdown(raw, id);
    FlowDocuments.Result parsed = FlowDocuments.parseAndValidate(markdown);
    if (!parsed.ok()) {
      String detail =
          parsed.diagnostics().stream()
              .filter(d -> d.severity() == FlowDiagnostic.Severity.ERROR)
              .map(FlowDiagnostic::message)
              .collect(Collectors.joining("; "));
      throw new IllegalArgumentException(
          "生成的 Flow 未通过静态校验: " + (detail.isBlank() ? "unknown" : detail));
    }
    if (!id.equals(parsed.definition().id())) {
      throw new IllegalArgumentException(
          "生成的 Flow id 不匹配: expected=" + id + " actual=" + parsed.definition().id());
    }
    return new Draft(id, markdown, parsed.definition());
  }

  static String slugify(String raw) {
    String s =
        raw == null
            ? ""
            : raw.strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    if (s.length() > 48) {
      s = s.substring(0, 48).replaceAll("-+$", "");
    }
    return s.isBlank() ? "flow" : s;
  }

  static String normalizeMarkdown(String raw, String id) {
    String text = raw.strip();
    Matcher fence = FENCE.matcher(text);
    if (fence.matches()) {
      text = fence.group(1).strip();
    }
    // drop leading prose before first ---
    int fenceStart = text.indexOf("---");
    if (fenceStart > 0) {
      text = text.substring(fenceStart).strip();
    }
    if (!text.startsWith("---")) {
      throw new IllegalArgumentException("生成结果缺少 YAML frontmatter（---）");
    }
    Matcher idLine = ID_LINE.matcher(text);
    if (idLine.find()) {
      text = idLine.replaceFirst("id: " + id);
    }
    return text;
  }
}
