package io.oryxos.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.cost.CostLedgerService;
import io.oryxos.core.cost.CostProperties;
import io.oryxos.core.cost.InMemoryCostLedgerStore;
import io.oryxos.core.provider.Usage;
import io.oryxos.web.error.ResourceNotFoundException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CostApiControllerTest {

  @Test
  @DisplayName("flag-off returns 404")
  void disabledReturns404() {
    CostProperties props = new CostProperties();
    props.setEnabled(false);
    CostLedgerService ledger =
        new CostLedgerService(props, new InMemoryCostLedgerStore(), id -> 0L);
    CostApiController controller = new CostApiController(ledger);
    assertThrows(
        ResourceNotFoundException.class,
        () -> controller.attribution(null, null, null, null, null, null, null, null));
  }

  @Test
  @DisplayName("enabled attribution returns price versions and totals")
  void attributionWhenEnabled() {
    CostProperties props = new CostProperties();
    props.setEnabled(true);
    InMemoryCostLedgerStore store = new InMemoryCostLedgerStore();
    CostLedgerService ledger = new CostLedgerService(props, store, id -> 0L);
    ledger.recordLlm("s", "agent", "p", "m", new Usage(1, 2, 3), 7L, 2L, 9);
    CostApiController controller = new CostApiController(ledger);
    Map<String, Object> body =
        controller.attribution(null, null, "agent", null, null, null, null, null).getData();
    assertEquals(7L, body.get("llmCostMicros"));
    assertEquals(java.util.List.of(2L), body.get("priceVersions"));
    assertTrue(((java.util.List<?>) body.get("entries")).size() >= 1);
  }

  @Test
  @DisplayName("attribution 返回 LLM/工具调用次数（053）")
  void attributionReturnsCallCounts() {
    CostProperties props = new CostProperties();
    props.setEnabled(true);
    InMemoryCostLedgerStore store = new InMemoryCostLedgerStore();
    CostLedgerService ledger = new CostLedgerService(props, store, id -> 0L);
    ledger.recordLlm("s", "agent", "p", "m", new Usage(1, 2, 3), 7L, 2L, 9);
    ledger.recordTool("s", "agent", "shell", 5);
    ledger.recordTool("s", "agent", "http_get", 3);
    CostApiController controller = new CostApiController(ledger);
    Map<String, Object> body =
        controller.attribution(null, null, "agent", null, null, null, null, null).getData();
    assertEquals(1L, body.get("llmCallCount"));
    assertEquals(2L, body.get("toolCallCount"));
  }

  @Test
  @DisplayName("facets 返回去重候选值，不含 run/task（053）")
  void facetsReturnsDistinctDimensions() {
    CostProperties props = new CostProperties();
    props.setEnabled(true);
    InMemoryCostLedgerStore store = new InMemoryCostLedgerStore();
    CostLedgerService ledger = new CostLedgerService(props, store, id -> 0L);
    ledger.recordLlm("s", "ops", "deepseek", "deepseek-chat", new Usage(1, 1, 2), 1L, 1L, 1);
    ledger.recordLlm("s", "research", "qwen", "qwen-plus", new Usage(1, 1, 2), 1L, 1L, 1);
    CostApiController controller = new CostApiController(ledger);
    Map<String, Object> body = controller.facets().getData();
    assertEquals(java.util.List.of("ops", "research"), body.get("agents"));
    assertEquals(java.util.List.of("deepseek", "qwen"), body.get("providers"));
    assertTrue(body.containsKey("models") && body.containsKey("teams"));
    assertTrue(!body.containsKey("runs") && !body.containsKey("tasks"));
  }

  @Test
  @DisplayName("daily 按天补零（053）")
  void dailyFillsZero() {
    CostProperties props = new CostProperties();
    props.setEnabled(true);
    CostLedgerService ledger =
        new CostLedgerService(props, new InMemoryCostLedgerStore(), id -> 0L);
    CostApiController controller = new CostApiController(ledger);
    String from = "2026-10-01T00:00:00Z";
    String to = "2026-10-04T00:00:00Z";
    // 期望天数用服务同款本地时区算，避免测试依赖运行环境时区
    java.time.ZoneId zone = java.time.ZoneId.systemDefault();
    java.time.LocalDate sd = java.time.Instant.parse(from).atZone(zone).toLocalDate();
    java.time.LocalDate ed = java.time.Instant.parse(to).minusMillis(1).atZone(zone).toLocalDate();
    int expectedDays = (int) (java.time.temporal.ChronoUnit.DAYS.between(sd, ed) + 1);
    java.util.List<Map<String, Object>> pts =
        controller.daily(null, null, null, null, null, null, from, to).getData();
    assertEquals(expectedDays, pts.size());
    assertTrue(pts.stream().allMatch(p -> ((Number) p.get("llmCostMicros")).longValue() == 0L));
  }

  @Test
  @DisplayName("daily 跨度超 92 天报可读错误（053）")
  void dailyRejectsTooWideRange() {
    CostProperties props = new CostProperties();
    props.setEnabled(true);
    CostLedgerService ledger =
        new CostLedgerService(props, new InMemoryCostLedgerStore(), id -> 0L);
    CostApiController controller = new CostApiController(ledger);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            controller.daily(
                null,
                null,
                null,
                null,
                null,
                null,
                "2026-01-01T00:00:00Z",
                "2026-12-31T00:00:00Z"));
  }

  @Test
  @DisplayName("tool-usage 按 agent 计数，之和=TOOL 条目数（053）")
  void toolUsageCountsByAgent() {
    CostProperties props = new CostProperties();
    props.setEnabled(true);
    CostLedgerService ledger =
        new CostLedgerService(props, new InMemoryCostLedgerStore(), id -> 0L);
    ledger.recordTool("s", "ops", "shell", 5);
    ledger.recordTool("s", "ops", "http_get", 3);
    ledger.recordTool("s", "research", "read_file", 2);
    CostApiController controller = new CostApiController(ledger);
    java.util.List<Map<String, Object>> rows =
        controller.toolUsage(null, null, null, null, null, null, null, null, false).getData();
    long sum = rows.stream().mapToLong(r -> ((Number) r.get("callCount")).longValue()).sum();
    assertEquals(3L, sum);
    // ops 2 次 → 排在最前（降序）
    assertEquals("ops", rows.get(0).get("agentName"));
    assertEquals(2L, ((Number) rows.get(0).get("callCount")).longValue());
  }

  @Test
  @DisplayName("facets flag-off 返回 404（053）")
  void facetsDisabledReturns404() {
    CostProperties props = new CostProperties();
    props.setEnabled(false);
    CostLedgerService ledger =
        new CostLedgerService(props, new InMemoryCostLedgerStore(), id -> 0L);
    CostApiController controller = new CostApiController(ledger);
    assertThrows(ResourceNotFoundException.class, controller::facets);
  }

  @Test
  @DisplayName("attribution 非法时间参数报可读错误（053）")
  void attributionRejectsBadInstant() {
    CostProperties props = new CostProperties();
    props.setEnabled(true);
    CostLedgerService ledger =
        new CostLedgerService(props, new InMemoryCostLedgerStore(), id -> 0L);
    CostApiController controller = new CostApiController(ledger);
    assertThrows(
        IllegalArgumentException.class,
        () -> controller.attribution(null, null, null, null, null, null, "not-a-time", null));
  }
}
