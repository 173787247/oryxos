package io.oryxos.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.oryxos.core.routing.InMemoryRoutingDecisionStore;
import io.oryxos.core.routing.ModelRoutingService;
import io.oryxos.core.routing.RoutingProperties;
import io.oryxos.web.error.ResourceNotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RoutingApiControllerTest {

  @Test
  void disabledReturns404() {
    RoutingProperties props = new RoutingProperties();
    props.setEnabled(false);
    ModelRoutingService svc =
        new ModelRoutingService(
            props, new InMemoryRoutingDecisionStore(16), (p, m) -> Optional.empty());
    RoutingApiController api = new RoutingApiController(svc);
    assertThrows(ResourceNotFoundException.class, () -> api.decisions(null, 10));
  }

  @Test
  void enabledReturnsEmptyList() {
    RoutingProperties props = new RoutingProperties();
    props.setEnabled(true);
    ModelRoutingService svc =
        new ModelRoutingService(
            props, new InMemoryRoutingDecisionStore(16), (p, m) -> Optional.empty());
    RoutingApiController api = new RoutingApiController(svc);
    assertEquals(
        0, ((java.util.List<?>) api.decisions(null, 10).getData().get("decisions")).size());
  }
}
