package io.oryxos.memory.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.ToolExecutionContext;
import io.oryxos.core.memory.MemoryRecallCapability;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.memory.Mem0MemoryStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.web.client.RestClient;

/**
 * 契约不变量的 mem0 真实档分支（015 T024，@Tag integration）——常驻 CI 的三档参数化套件在 {@link
 * MemoryBackendContractTest}；本类把可落在 DELEGATED 真实档上的不变量逐条核验（SC-010/011）。
 * 语义检索档的「关键词大小写统一」由语义匹配天然覆盖（不变量 5 在此档以语义命中形式断言）； 「零丢失/确定性」的本地索引面不适用（DELEGATED 不建索引），故障面以可读异常口径核验。
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Mem0BackendContractIT {

  private static final String BASE_URL =
      System.getenv().getOrDefault("MEM0_BASE_URL", "http://localhost:8888");

  private Mem0MemoryStore store;

  @BeforeAll
  void requireServer() {
    Assumptions.assumeTrue(
        serverUp(), "mem0 未启动（docker compose -f docker/mem0/compose.yaml up -d）");
    store =
        new Mem0MemoryStore(
            RestClient.builder().baseUrl(BASE_URL).build(),
            "oryxos-contract-" + UUID.randomUUID().toString().substring(0, 8));
  }

  @AfterEach
  void clearContext() {
    ToolExecutionContext.clear();
  }

  private static boolean serverUp() {
    try {
      HttpResponse<String> resp =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(2))
              .build()
              .send(
                  HttpRequest.newBuilder(URI.create(BASE_URL + "/")).GET().build(),
                  HttpResponse.BodyHandlers.ofString());
      return resp.statusCode() < 500;
    } catch (Exception e) {
      return false;
    }
  }

  @Test
  @DisplayName("不变量1_写入即可见（core 原文经 load 立即可读）")
  void appendIsImmediatelyVisible() {
    String fact = "立即可见断言 " + UUID.randomUUID();
    store.append(fact, MemoryScope.CORE);

    assertTrue(store.load().contains(fact), "写完下一次 load 立即反映（不缓存）");
  }

  @Test
  @DisplayName("不变量2+3_scope 显式路由_核心区不参与检索")
  void scopeRoutesAndCoreStaysOutOfSearch() {
    String coreToken = "core" + UUID.randomUUID().toString().substring(0, 8);
    String archToken = "arch" + UUID.randomUUID().toString().substring(0, 8);
    store.append("核心事实 " + coreToken, MemoryScope.CORE);
    store.append("归档事件 " + archToken + "：巡检发现磁盘告警", MemoryScope.ARCHIVAL);

    List<String> coreHits = store.recallByKeyword(coreToken);
    String loaded = store.load();

    assertTrue(coreHits.stream().noneMatch(h -> h.contains(coreToken)), "核心区不进检索");
    assertTrue(loaded.contains(coreToken), "核心区照常注入");
  }

  @Test
  @DisplayName("不变量4_per-Agent 隔离_A 的检索绝不命中 B（user_id 分段）")
  void agentsAreIsolated() {
    String secret = "isolate" + UUID.randomUUID().toString().substring(0, 8);
    ToolExecutionContext.setAgentName("agent-a");
    store.append("属于 A 的秘密 " + secret, MemoryScope.ARCHIVAL);
    ToolExecutionContext.setAgentName("agent-b");

    assertTrue(
        store.recallByKeyword(secret).stream().noneMatch(h -> h.contains(secret)), "B 检索不到 A 的条目");
    assertTrue(!store.load().contains(secret), "B 的注入也不带 A 的条目");
  }

  @Test
  @DisplayName("不变量5_语义档大小写无碍（ops-4721 命中 OPS-4721 记忆）")
  void caseDoesNotBlockSemanticRecall() {
    store.append("工单 OPS-4721 已升级到二线处理", MemoryScope.ARCHIVAL);

    List<String> hits = store.recallByKeyword("ops-4721 工单现在什么状态");

    assertTrue(hits.stream().anyMatch(h -> h.toLowerCase().contains("ops-4721")), "大小写不阻断命中");
  }

  @Test
  @DisplayName("不变量6_故障可读_连接失败转 IllegalStateException")
  void failureIsReadable() {
    Mem0MemoryStore unreachable =
        new Mem0MemoryStore(
            RestClient.builder().baseUrl("http://127.0.0.1:59999").build(), "contract-down");

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> unreachable.recallByKeyword("x"));
    assertTrue(ex.getMessage().contains("mem0"), "故障口径可读、点名后端");
  }

  @Test
  @DisplayName("不变量8_确定性_同查询连续两次结果一致")
  void recallIsDeterministicAcrossCalls() {
    store.append("确定性断言样本：数据库连接池上限 200", MemoryScope.ARCHIVAL);

    String query = "连接池上限是多少";
    assertEquals(store.recallByKeyword(query), store.recallByKeyword(query), "同输入恒同输出");
  }

  @Test
  @DisplayName("能力申明_DELEGATED 三同形状")
  void delegatedShape() {
    assertEquals(MemoryRecallCapability.DELEGATED, store.capabilities());
    assertTrue(store.archivalEntries().isEmpty(), "引擎取数口不适用");
  }
}
