package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.memory.MemoryScope;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.web.client.RestClient;

/**
 * US5 真实 mem0 联调（015 T023，@Tag integration 默认不进 CI）：需先启动 {@code docker compose -f
 * docker/mem0/compose.yaml up -d --build}（quickstart §F）。 server 不可达时整类假设跳过（不误报红）。每次运行用随机 user
 * 前缀，互不污染。
 *
 * <p>运行：{@code mvn -pl oryxos-memory test -Dgroups=integration -Dtest=Mem0FlowIT}
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Mem0FlowIT {

  private static final String BASE_URL =
      System.getenv().getOrDefault("MEM0_BASE_URL", "http://localhost:8888");

  private Mem0MemoryStore store;

  @BeforeAll
  void requireServerAndBuildStore() {
    Assumptions.assumeTrue(
        serverUp(), "mem0 未启动（docker compose -f docker/mem0/compose.yaml up -d）");
    store =
        new Mem0MemoryStore(
            RestClient.builder().baseUrl(BASE_URL).build(),
            "oryxos-it-" + UUID.randomUUID().toString().substring(0, 8));
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
  @DisplayName("场景1_core 原文逐字保真_每轮注入完整出现（infer:false）")
  void coreIsStoredVerbatimAndInjected() {
    String fact = "我叫小林，永远用中文回复（唯一标记 " + UUID.randomUUID() + "）";
    store.append(fact, MemoryScope.CORE);

    String loaded = store.load();

    assertTrue(loaded.contains(fact), "core 原文一字不差出现在注入内容里");
    assertTrue(loaded.indexOf(fact) < loaded.indexOf("## 归档记忆"), "落在核心区块");
  }

  @Test
  @DisplayName("场景2_archival 提炼后语义命中（infer:true + search）")
  void archivalIsSemanticallySearchable() {
    store.append("上周四生产发布在灰度环节出了故障，回滚后改成分批放量策略", MemoryScope.ARCHIVAL);

    List<String> hits = store.recallByKeyword("部署出问题后是怎么处理的");

    assertFalse(hits.isEmpty(), "语义检索应命中提炼后的归档记忆");
  }

  @Test
  @DisplayName("场景3_分区过滤实测_core 不进检索结果（#3773 版本核验）")
  void coreNeverLeaksIntoSearchResults() {
    String coreToken = "coreonly" + UUID.randomUUID().toString().substring(0, 8);
    store.append("核心专属事实 " + coreToken, MemoryScope.CORE);

    List<String> hits = store.recallByKeyword(coreToken);

    assertTrue(
        hits.stream().noneMatch(h -> h.contains(coreToken)), "核心区条目绝不进检索结果——filters 失效则适配器返回侧过滤兜底");
  }

  @Test
  @DisplayName("场景4_服务不可达_读写均为可读错误（对话不中断的前提）")
  void downtimeYieldsReadableErrors() {
    Mem0MemoryStore unreachable =
        new Mem0MemoryStore(
            RestClient.builder().baseUrl("http://127.0.0.1:59999").build(), "oryxos-it-down");

    IllegalStateException readErr =
        assertThrows(IllegalStateException.class, () -> unreachable.recallByKeyword("x"));
    IllegalStateException writeErr =
        assertThrows(
            IllegalStateException.class, () -> unreachable.append("x", MemoryScope.ARCHIVAL));

    assertTrue(readErr.getMessage().contains("mem0"), "检索失败可读");
    assertTrue(writeErr.getMessage().contains("写入"), "写失败如实呈现");
  }
}
