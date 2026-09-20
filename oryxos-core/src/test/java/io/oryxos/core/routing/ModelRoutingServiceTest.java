package io.oryxos.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.TraceContext;
import io.oryxos.core.cost.CostLedgerService;
import io.oryxos.core.cost.CostProperties;
import io.oryxos.core.cost.InMemoryCostLedgerStore;
import io.oryxos.core.cost.OverBudgetAction;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.ModelPricing;
import io.oryxos.core.provider.PricingStore;
import io.oryxos.core.provider.Usage;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelRoutingServiceTest {

  private RoutingProperties props;
  private InMemoryRoutingDecisionStore store;
  private ModelRoutingService service;
  private PricingStore pricing;

  @BeforeEach
  void setUp() {
    props = new RoutingProperties();
    props.setEnabled(true);
    store = new InMemoryRoutingDecisionStore(64);
    pricing =
        (provider, model) -> {
          if ("cheap".equals(provider)) {
            return Optional.of(new ModelPricing(provider, model, 1.0, 1.0, 1L));
          }
          if ("pricey".equals(provider)) {
            return Optional.of(new ModelPricing(provider, model, 100.0, 100.0, 1L));
          }
          return Optional.empty();
        };
    service = new ModelRoutingService(props, store, pricing);
  }

  private static Profile profile(
      String primary, String model, Profile.ProviderRef.FallbackRef... fbs) {
    return new Profile(
        "agent-a",
        "d",
        null,
        null,
        new Profile.ProviderRef(primary, model, null, List.of(fbs)),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }

  @Test
  @DisplayName("flag-off: declared order unchanged and nothing recorded")
  void flagOffIsNoop() {
    props.setEnabled(false);
    Profile p = profile("pricey", "m1", new Profile.ProviderRef.FallbackRef("cheap", "flash"));
    RoutingDecision d = service.route(p);
    assertEquals("pricey", d.selectedProvider());
    assertEquals(2, d.attemptOrder().size());
    assertTrue(store.recent(10).isEmpty());
  }

  @Test
  @DisplayName("sensitivity residency filters non-allowlisted providers")
  void residencyFilter() {
    props.getResidencyProviders().add("cheap");
    Profile p = profile("pricey", "m1", new Profile.ProviderRef.FallbackRef("cheap", "flash"));
    try (RoutingContext.Scope scope =
        RoutingContext.open(null, DataSensitivity.SENSITIVE, null, null)) {
      RoutingDecision d = service.route(p);
      assertEquals("cheap", d.selectedProvider());
      assertTrue(
          d.reasons().stream().anyMatch(r -> r.code() == RoutingReasonCode.SENSITIVITY_RESIDENCY));
      assertTrue(d.candidates().stream().anyMatch(c -> "FILTERED".equals(c.status())));
    }
    assertFalse(store.recent(5).isEmpty());
  }

  @Test
  @DisplayName("residency with no matching candidate fails closed")
  void residencyFailClosed() {
    props.getResidencyProviders().add("local-only");
    Profile p = profile("pricey", "m1");
    try (RoutingContext.Scope scope =
        RoutingContext.open(null, DataSensitivity.SENSITIVE, null, null)) {
      assertThrows(ResidencyConstraintException.class, () -> service.route(p));
    }
  }

  @Test
  @DisplayName("difficulty promotes preferred candidate within allowlist only")
  void difficultyPromote() {
    ProviderModelRef ref = new ProviderModelRef();
    ref.setProvider("cheap");
    ref.setModel("flash");
    props.getDifficultyModels().put("low", ref);
    Profile p = profile("pricey", "m1", new Profile.ProviderRef.FallbackRef("cheap", "flash"));
    try (RoutingContext.Scope scope = RoutingContext.open(TaskDifficulty.LOW, null, null, null)) {
      RoutingDecision d = service.route(p);
      assertEquals("cheap", d.selectedProvider());
      assertTrue(d.reasons().stream().anyMatch(r -> r.code() == RoutingReasonCode.DIFFICULTY));
    }
  }

  @Test
  @DisplayName("difficulty outside allowlist is ignored (no privilege expansion)")
  void difficultyNoExpansion() {
    ProviderModelRef ref = new ProviderModelRef();
    ref.setProvider("other");
    ref.setModel("x");
    props.getDifficultyModels().put("high", ref);
    Profile p = profile("pricey", "m1");
    try (RoutingContext.Scope scope = RoutingContext.open(TaskDifficulty.HIGH, null, null, null)) {
      RoutingDecision d = service.route(p);
      assertEquals("pricey", d.selectedProvider());
      assertTrue(
          d.reasons().stream()
              .anyMatch(
                  r ->
                      r.code() == RoutingReasonCode.DIFFICULTY
                          && r.detail().contains("not in Agent allowlist")));
    }
  }

  @Test
  @DisplayName("preferCheapest reorders by llm_pricing")
  void preferCheapest() {
    props.setPreferCheapest(true);
    Profile p = profile("pricey", "m1", new Profile.ProviderRef.FallbackRef("cheap", "flash"));
    try (RoutingContext.Scope scope = RoutingContext.open(null, null, null, 1000L)) {
      RoutingDecision d = service.route(p);
      assertEquals("cheap", d.selectedProvider());
      assertTrue(
          d.reasons().stream().anyMatch(r -> r.code() == RoutingReasonCode.COST_PREFER_CHEAP));
    }
  }

  @Test
  @DisplayName("budget degrade from cost ledger promotes within allowlist")
  void budgetSignal() {
    CostProperties costProps = new CostProperties();
    costProps.setEnabled(true);
    costProps.setEnforcementEnabled(true);
    costProps.setOverBudgetAction(OverBudgetAction.DEGRADE);
    costProps.setDegradeProvider("cheap");
    costProps.setDegradeModel("flash");
    CostProperties.BudgetRule rule = new CostProperties.BudgetRule();
    rule.setScope(io.oryxos.core.cost.BudgetScope.AGENT);
    rule.setKey("agent-a");
    rule.setLimitMicros(1);
    costProps.getBudgets().add(rule);
    CostLedgerService ledger =
        new CostLedgerService(costProps, new InMemoryCostLedgerStore(), id -> 0L);
    ledger.recordLlm("s", "agent-a", "pricey", "m1", new Usage(1, 1, 2), 10L, 1L, 1);
    service.setCostLedgerService(ledger);

    Profile p = profile("pricey", "m1", new Profile.ProviderRef.FallbackRef("cheap", "flash"));
    RoutingDecision d = service.route(p);
    assertEquals("cheap", d.selectedProvider());
    assertTrue(d.reasons().stream().anyMatch(r -> r.code() == RoutingReasonCode.BUDGET));
  }

  @Test
  @DisplayName("fallback switch is recorded with FALLBACK reason")
  void fallbackRecorded() {
    Profile p = profile("pricey", "m1");
    try (TraceContext.Scope scope = TraceContext.open("run-fb")) {
      service.recordFallback(p, "pricey", "m1", "cheap", "flash", "timeout");
    }
    List<RoutingDecision> found = store.findByRunId("run-fb");
    assertEquals(1, found.size());
    assertEquals(RoutingReasonCode.FALLBACK, found.get(0).reasons().get(0).code());
    assertEquals("cheap", found.get(0).selectedProvider());
  }

  @Test
  @DisplayName("latency preference promotes configured latency model")
  void latencyPromote() {
    props.setMaxLatencyMs(100);
    ProviderModelRef lat = new ProviderModelRef();
    lat.setProvider("cheap");
    lat.setModel("flash");
    props.setLatencyModel(lat);
    Profile p = profile("pricey", "m1", new Profile.ProviderRef.FallbackRef("cheap", "flash"));
    RoutingDecision d = service.route(p);
    assertEquals("cheap", d.selectedProvider());
    assertTrue(d.reasons().stream().anyMatch(r -> r.code() == RoutingReasonCode.LATENCY));
  }
}
