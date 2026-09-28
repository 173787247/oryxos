package io.oryxos.web.controller;

import io.oryxos.core.capability.CapabilityCatalog;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.CapabilityView;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Direction C：统一能力目录（装配 / Flow 搭积木）。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "Spring MVC injects catalog; same pattern as other list controllers.")
@RestController
@RequestMapping("/api/v1/capabilities")
@ConditionalOnBean(CapabilityCatalog.class)
public class CapabilityApiController {

  private final CapabilityCatalog catalog;

  public CapabilityApiController(CapabilityCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog);
  }

  @GetMapping
  public ApiResponse<List<CapabilityView>> list() {
    return ApiResponse.ok(catalog.list().stream().map(CapabilityView::from).toList());
  }
}
