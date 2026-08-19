package io.oryxos.knowledge.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.agent.ProfileContext;
import io.oryxos.core.knowledge.KnowledgeService;
import io.oryxos.core.knowledge.model.KnowledgeHit;
import io.oryxos.core.profile.Profile;
import java.nio.file.Path;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置检索工具 {@code retrieve_knowledge}（FR-004）：只认 {@link KnowledgeService} 门面，检索范围由门面按 发起 Agent
 * 的绑定圈定（经 {@link ProfileContext}，MemoryTools 同款模式）。
 *
 * <p>工具结果是结构化 JSON——同时是 FR-022 的前瞻埋点载体（命中明细 + 分数 + 零结果/降级标记 + 查询原文 + 耗时），随 {@code tool_invocations}
 * 审计落库，后续评测集/优化建议直接消费，无需二次埋点。 检索范围类错误返回可读文本（不抛栈），对话不中断（SC-005）。
 */
public class KnowledgeTools {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final KnowledgeService knowledgeService;
  private final Path knowledgeRoot;

  public KnowledgeTools(KnowledgeService knowledgeService, Path knowledgeRoot) {
    this.knowledgeService = knowledgeService;
    this.knowledgeRoot = knowledgeRoot.toAbsolutePath().normalize();
  }

  @Tool(
      name = "retrieve_knowledge",
      description =
          "在当前 Agent 绑定的知识库中检索企业知识，返回带出处的片段。片段是入口：不足以回答时按结果里的 file 绝对路径用 read_file 读取原文补充")
  public String retrieveKnowledge(
      @ToolParam(description = "检索查询（可对用户原话改写）") String query,
      @ToolParam(required = false, description = "返回片段数上限，默认 5") Integer limit,
      @ToolParam(required = false, description = "限定单个知识库名；缺省聚合全部绑定库") String knowledgeBase) {
    long start = System.currentTimeMillis();
    Profile profile = ProfileContext.current();
    if (profile == null) {
      return "检索失败: 无法确定当前 Agent（缺少运行上下文）";
    }
    String kbFilter = knowledgeBase == null || knowledgeBase.isBlank() ? null : knowledgeBase;
    List<KnowledgeHit> hits;
    try {
      hits = knowledgeService.retrieveForAgent(profile.name(), query, limit, kbFilter);
    } catch (IllegalArgumentException e) {
      // 零绑定 / 限定未绑定库等范围错误：可读文本返回，不中断对话（FR-020 / SC-005）
      return "检索失败: " + e.getMessage();
    }
    return render(query, hits, System.currentTimeMillis() - start);
  }

  /** contracts/knowledge-spi.md §3 的结构化结果（同时是 FR-022 埋点结构，一次设计到位）。 */
  private String render(String query, List<KnowledgeHit> hits, long durationMs) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("query", query);
    ArrayNode hitNodes = root.putArray("hits");
    boolean anyDegraded = false;
    for (KnowledgeHit hit : hits) {
      anyDegraded |= hit.degraded();
      ObjectNode node = hitNodes.addObject();
      node.put("kb", hit.citation().kbName());
      node.put("path", hit.citation().relPath());
      node.put("position", hit.citation().position());
      node.put("citation", hit.citation().display());
      node.put("readable", hit.citation().readable());
      if (hit.citation().readable() && !hit.citation().relPath().isBlank()) {
        node.put(
            "file",
            knowledgeRoot
                .resolve(hit.citation().kbName())
                .resolve(hit.citation().relPath())
                .toString());
      }
      node.put("score", hit.score());
      node.put("degraded", hit.degraded());
      node.put("content", hit.content());
      Object reason = hit.payload().get("degraded_reason");
      if (reason != null) {
        node.put("degraded_reason", String.valueOf(reason));
      }
    }
    root.put("zero_result", hits.isEmpty());
    root.put("degraded", anyDegraded);
    root.put("duration_ms", durationMs);
    try {
      return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("检索结果序列化失败", e);
    }
  }
}
