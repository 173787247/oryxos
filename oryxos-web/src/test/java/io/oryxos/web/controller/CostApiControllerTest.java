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
        () -> controller.attribution(null, null, null, null, null, null));
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
        controller.attribution(null, null, "agent", null, null, null).getData();
    assertEquals(7L, body.get("llmCostMicros"));
    assertEquals(java.util.List.of(2L), body.get("priceVersions"));
    assertTrue(((java.util.List<?>) body.get("entries")).size() >= 1);
  }
}
