package io.oryxos.knowledge.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.knowledge.KnowledgeAdmin;
import io.oryxos.core.knowledge.KnowledgeBackend;
import io.oryxos.core.knowledge.KnowledgeBackendRegistry;
import io.oryxos.core.knowledge.KnowledgeBindingService;
import io.oryxos.core.knowledge.KnowledgeServiceImpl;
import io.oryxos.core.knowledge.model.Citation;
import io.oryxos.core.knowledge.model.KnowledgeCapabilities;
import io.oryxos.core.knowledge.model.KnowledgeHit;
import io.oryxos.core.knowledge.model.KnowledgeQuery;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T045（US5 / SC-011）：仅检索能力的远程测试桩钉死插件契约「三同」——同一工具入口（门面按清单 backend
 * 路由）、同一出处契约（缺出处显式标注不可用，绝不给假路径）、同一错误语义（不可达可读 报错）；能力声明诚实（admin 为 empty，管理面门禁在 REST 层已由
 * KnowledgeApiControllerTest 覆盖）。
 */
class StubRemoteBackendContractTest {

  @TempDir Path root;

  private StubRemoteBackend stub;
  private KnowledgeServiceImpl service;

  @BeforeEach
  void setUp() throws IOException {
    Path kbRoot = Files.createDirectories(root.resolve("knowledge"));
    Path remote = Files.createDirectories(kbRoot.resolve("remote-kb"));
    Files.writeString(
        remote.resolve("KNOWLEDGE.md"),
        "---\nname: remote-kb\ndescription: 远程库\nbackend: stub\n"
            + "connection:\n  base_url: https://ragflow.internal\n  api_key: ${STUB_KEY}\n---\n");
    Files.createDirectories(root.resolve("agents"));
    Files.createDirectories(root.resolve("agents/ops"));
    Files.writeString(root.resolve("agents/ops/AGENT.md"), "---\nname: ops\n---\nbody");
    stub = new StubRemoteBackend();
    KnowledgeBackendRegistry registry = new KnowledgeBackendRegistry();
    registry.register(stub);
    KnowledgeBindingService bindings = new KnowledgeBindingService(root);
    bindings.bind("ops", "remote-kb");
    service = new KnowledgeServiceImpl(kbRoot, bindings, registry);
  }

  @Test
  @DisplayName("三同之一：同一门面入口——按清单 backend 声明路由到远程桩")
  void routedThroughSameFacadeByManifestBackend() {
    stub.hits =
        List.of(
            new KnowledgeHit(
                new Citation("remote-kb", "guide.md", "3", false), "远程片段", 0.9, false, Map.of()));

    List<KnowledgeHit> hits = service.retrieveForAgent("ops", "查询", 5, null);

    assertEquals(1, hits.size());
    assertEquals("remote-kb", stub.lastQuery.kbNames().get(0), "检索范围由门面圈定后传入插件");
    assertEquals("查询", stub.lastQuery.query());
  }

  @Test
  @DisplayName("三同之二：出处契约——远程缺出处显式标注「出处不可用」，绝不返回假路径")
  void missingCitationIsExplicitlyMarkedUnavailable() {
    stub.hits =
        List.of(
            new KnowledgeHit(
                new Citation("remote-kb", "", "出处不可用", false), "无出处片段", 0.5, false, Map.of()));

    KnowledgeHit hit = service.retrieveForAgent("ops", "查询", 5, null).get(0);

    assertFalse(hit.citation().readable(), "远程无本地文件不可跟读");
    assertTrue(hit.citation().display().contains(Citation.UNAVAILABLE));
  }

  @Test
  @DisplayName("三同之三：错误语义——远程不可达抛可读异常（工具层转可读结果并入审计，对话不中断）")
  void unreachableBackendFailsReadably() {
    stub.unreachable = true;

    RuntimeException failure =
        assertThrows(RuntimeException.class, () -> service.retrieveForAgent("ops", "查询", 5, null));

    assertTrue(failure.getMessage().contains("不可达"), "错误消息可读点名原因");
  }

  @Test
  @DisplayName("能力声明诚实：仅检索 → admin 为 empty、全管理能力位为 false")
  void capabilitiesAreHonest() {
    assertEquals(KnowledgeCapabilities.retrieveOnly(), stub.capabilities());
    assertTrue(stub.admin().isEmpty(), "未声明管理能力就不得暴露 admin（规避契约谎言）");
  }

  /** 仅检索能力的远程桩：可配置命中与「不可达」。 */
  static final class StubRemoteBackend implements KnowledgeBackend {

    List<KnowledgeHit> hits = List.of();
    boolean unreachable;
    KnowledgeQuery lastQuery;

    @Override
    public String name() {
      return "stub";
    }

    @Override
    public KnowledgeCapabilities capabilities() {
      return KnowledgeCapabilities.retrieveOnly();
    }

    @Override
    public Optional<KnowledgeAdmin> admin() {
      return Optional.empty();
    }

    @Override
    public List<KnowledgeHit> retrieve(KnowledgeQuery query) {
      if (unreachable) {
        throw new IllegalStateException("远程知识库后端不可达: https://ragflow.internal");
      }
      lastQuery = query;
      return hits;
    }
  }
}
