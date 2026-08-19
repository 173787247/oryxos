package io.oryxos.knowledge.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.ProfileContext;
import io.oryxos.core.knowledge.KnowledgeService;
import io.oryxos.core.knowledge.model.Citation;
import io.oryxos.core.knowledge.model.KnowledgeBaseInfo;
import io.oryxos.core.knowledge.model.KnowledgeHit;
import io.oryxos.core.profile.Profile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T023：retrieve_knowledge——绑定范围经 ProfileContext 圈定、结果为 FR-022 埋点结构、范围错误可读返回。 */
class KnowledgeToolsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path knowledgeRoot;

  private String lastAgent;
  private Integer lastTopK;
  private String lastKbFilter;

  @AfterEach
  void tearDown() {
    ProfileContext.clear();
  }

  @Test
  void scopesByCurrentAgentAndEmitsStructuredAuditPayload() throws Exception {
    KnowledgeHit hit =
        new KnowledgeHit(
            new Citation("ops-manual", "disk.md", "3", true), "处置步骤", 0.42, false, Map.of());
    KnowledgeTools tools = new KnowledgeTools(service(List.of(hit)), knowledgeRoot);
    ProfileContext.set(profile("ops-agent"));

    String result = tools.retrieveKnowledge("磁盘告警", 3, "ops-manual");

    assertEquals("ops-agent", lastAgent, "检索范围 = 发起 Agent 的绑定（FR-004）");
    assertEquals(3, lastTopK);
    assertEquals("ops-manual", lastKbFilter, "工具参数可限定单库（FR-020）");
    JsonNode json = MAPPER.readTree(result);
    // FR-022 埋点一次到位：查询原文 + 命中明细（库/文档/位置/分数）+ 标记 + 耗时
    assertEquals("磁盘告警", json.get("query").asText());
    JsonNode first = json.get("hits").get(0);
    assertEquals("ops-manual", first.get("kb").asText());
    assertEquals("disk.md", first.get("path").asText());
    assertEquals("3", first.get("position").asText());
    assertEquals("[ops-manual] disk.md #3", first.get("citation").asText());
    assertTrue(first.get("file").asText().endsWith("ops-manual/disk.md"), "本地命中给出可跟读绝对路径（FR-017）");
    assertFalse(json.get("zero_result").asBoolean());
    assertFalse(json.get("degraded").asBoolean());
    assertTrue(json.has("duration_ms"));
  }

  @Test
  void degradedHitsAreFlaggedAndUnreadableCitationHasNoFilePath() throws Exception {
    KnowledgeHit degraded =
        new KnowledgeHit(
            new Citation("remote-kb", "", "出处不可用", false),
            "远程片段",
            0.2,
            true,
            Map.of("degraded_reason", "向量化服务不可用"));
    KnowledgeTools tools = new KnowledgeTools(service(List.of(degraded)), knowledgeRoot);
    ProfileContext.set(profile("ops-agent"));

    JsonNode json = MAPPER.readTree(tools.retrieveKnowledge("查询", null, null));

    JsonNode first = json.get("hits").get(0);
    assertTrue(first.get("degraded").asBoolean());
    assertTrue(json.get("degraded").asBoolean());
    assertFalse(first.has("file"), "不可跟读命中绝不返回不可用路径（FR-017）");
    assertTrue(first.get("degraded_reason").asText().contains("向量化"));
  }

  @Test
  void scopeErrorsReturnReadableTextWithoutBreakingConversation() {
    KnowledgeService failing =
        new KnowledgeService() {
          @Override
          public List<KnowledgeHit> retrieveForAgent(
              String agentName, String query, Integer topK, String kbNameOrNull) {
            throw new IllegalArgumentException("当前 Agent 未绑定任何知识库");
          }

          @Override
          public List<KnowledgeBaseInfo> listBases() {
            return List.of();
          }
        };
    KnowledgeTools tools = new KnowledgeTools(failing, knowledgeRoot);
    ProfileContext.set(profile("lonely"));

    String result = tools.retrieveKnowledge("任何查询", null, null);

    assertTrue(result.startsWith("检索失败:"), "零绑定误调返回可读错误（SC-005），不抛栈");
    assertTrue(result.contains("未绑定"));
  }

  @Test
  void missingProfileContextIsReadablyRejected() {
    KnowledgeTools tools = new KnowledgeTools(service(List.of()), knowledgeRoot);
    ProfileContext.clear();
    assertTrue(tools.retrieveKnowledge("查询", null, null).contains("无法确定当前 Agent"));
  }

  private KnowledgeService service(List<KnowledgeHit> hits) {
    return new KnowledgeService() {
      @Override
      public List<KnowledgeHit> retrieveForAgent(
          String agentName, String query, Integer topK, String kbNameOrNull) {
        lastAgent = agentName;
        lastTopK = topK;
        lastKbFilter = kbNameOrNull;
        return hits;
      }

      @Override
      public List<KnowledgeBaseInfo> listBases() {
        return List.of();
      }
    };
  }

  private static Profile profile(String name) {
    return new Profile(
        name,
        null,
        null,
        new Profile.ProviderRef("mock", "mock", null),
        List.of("retrieve_knowledge"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }
}
