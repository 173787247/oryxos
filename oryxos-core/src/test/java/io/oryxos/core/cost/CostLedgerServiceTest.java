package io.oryxos.core.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.TraceContext;
import io.oryxos.core.provider.Usage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CostLedgerServiceTest {

  private InMemoryCostLedgerStore store;
  private CostProperties props;
  private AtomicLong auditMicros;
  private CostLedgerService service;

  @BeforeEach
  void setUp() {
    store = new InMemoryCostLedgerStore();
    props = new CostProperties();
    props.setEnabled(true);
    props.setEnforcementEnabled(true);
    props.setDefaultToolCostMicros(100);
    Map<String, Long> tools = new HashMap<>();
    tools.put("shell", 500L);
    props.setToolCosts(tools);
    auditMicros = new AtomicLong();
    service = new CostLedgerService(props, store, id -> auditMicros.get());
  }

  @Test
  @DisplayName("flag-off: no ledger rows and budgets never block")
  void flagOffIsNoop() {
    props.setEnabled(false);
    props.setEnforcementEnabled(true);
    CostProperties.BudgetRule rule = new CostProperties.BudgetRule();
    rule.setScope(BudgetScope.AGENT);
    rule.setKey("a1");
    rule.setLimitMicros(1);
    props.getBudgets().add(rule);

    assertNull(service.recordLlm("s", "a1", "p", "m", new Usage(1, 1, 2), 999L, 1L, 10));
    assertTrue(service.checkBudget("a1", null, "m", "run").allowed());
    assertTrue(
        service
            .query(new CostAttributionQuery(null, null, "a1", null, null, null))
            .entries()
            .isEmpty());
  }

  @Test
  @DisplayName("records attribution with price version and aggregates by agent")
  void attributionAndPriceVersion() {
    try (TraceContext.Scope scope = TraceContext.open("run-1");
        CostContext.Scope cost = CostContext.open("task-1", "team-x", "run-1")) {
      service.recordLlm("sess", "agent-a", "deepseek", "m1", new Usage(10, 20, 30), 1000L, 3L, 40);
      service.recordTool("sess", "agent-a", "shell", 5);
    }
    CostAttributionSummary summary =
        service.query(new CostAttributionQuery(null, null, "agent-a", null, null, null));
    assertEquals(
        1, summary.entries().stream().filter(e -> e.sourceKind() == CostSourceKind.LLM).count());
    assertEquals(1000L, summary.llmCostMicros());
    assertEquals(500L, summary.toolCostMicros());
    assertEquals(1500L, summary.totalCostMicros());
    assertEquals(45L, summary.totalLatencyMs());
    assertEquals(java.util.List.of(3L), summary.priceVersions());
    assertEquals("team-x", summary.entries().get(0).teamId());
    assertEquals("task-1", summary.entries().get(0).taskId());
  }

  @Test
  @DisplayName("over budget BLOCK")
  void overBudgetBlocks() {
    CostProperties.BudgetRule rule = new CostProperties.BudgetRule();
    rule.setId("cap");
    rule.setScope(BudgetScope.AGENT);
    rule.setKey("agent-a");
    rule.setLimitMicros(500);
    props.getBudgets().add(rule);
    props.setOverBudgetAction(OverBudgetAction.BLOCK);

    service.recordLlm("s", "agent-a", "p", "m", new Usage(1, 1, 2), 500L, 1L, 1);
    BudgetDecision d = service.checkBudget("agent-a", null, "m", "r");
    assertFalse(d.allowed());
    assertEquals(OverBudgetAction.BLOCK, d.action());
  }

  @Test
  @DisplayName("over budget DEGRADE returns cheap model override")
  void overBudgetDegrades() {
    CostProperties.BudgetRule rule = new CostProperties.BudgetRule();
    rule.setScope(BudgetScope.AGENT);
    rule.setKey("agent-a");
    rule.setLimitMicros(100);
    props.getBudgets().add(rule);
    props.setOverBudgetAction(OverBudgetAction.DEGRADE);
    props.setDegradeProvider("cheap");
    props.setDegradeModel("flash");

    service.recordLlm("s", "agent-a", "p", "m", new Usage(1, 1, 2), 100L, 1L, 1);
    BudgetDecision d = service.checkBudget("agent-a", null, "m", "r");
    assertTrue(d.allowed());
    assertTrue(d.isDegrade());
    assertEquals("cheap", d.degradeProvider());
    assertEquals("flash", d.degradeModel());
  }

  @Test
  @DisplayName("reconcile matches audit llm costs for a run")
  void reconcileMatchesAudit() {
    try (TraceContext.Scope scope = TraceContext.open("run-9")) {
      service.recordLlm("s", "a", "p", "m", new Usage(1, 1, 2), 42L, 1L, 1);
      service.recordTool("s", "a", "shell", 1);
    }
    auditMicros.set(42L);
    CostReconcileResult r = service.reconcile("run-9");
    assertTrue(r.matched());
    assertEquals(42L, r.ledgerLlmCostMicros());
    assertEquals(500L, r.ledgerToolCostMicros());
    assertEquals(42L, r.auditLlmCostMicros());
  }

  @Test
  @DisplayName("BudgetExceededException message surfaces rule detail")
  void blockReasonSurfaced() {
    CostProperties.BudgetRule rule = new CostProperties.BudgetRule();
    rule.setId("cap");
    rule.setScope(BudgetScope.RUN);
    rule.setKey("run-z");
    rule.setLimitMicros(10);
    props.getBudgets().add(rule);
    service.recordLlm("s", "a", "p", "m", new Usage(1, 1, 2), 10L, 1L, 1);
    // force run attribution
    store.append(
        new CostLedgerEntry(
            0,
            "run-z",
            "t",
            "a",
            null,
            "p",
            "m",
            CostSourceKind.LLM,
            "p/m",
            1,
            1,
            2,
            10,
            0,
            1,
            1L,
            "s",
            "run-z",
            java.time.Instant.now()));
    BudgetDecision d = service.checkBudget("a", null, "m", "run-z");
    assertFalse(d.allowed());
    assertThrows(
        BudgetExceededException.class,
        () -> {
          throw new BudgetExceededException(d.reason());
        });
  }
}
