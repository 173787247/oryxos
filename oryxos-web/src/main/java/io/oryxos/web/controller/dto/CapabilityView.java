package io.oryxos.web.controller.dto;

import io.oryxos.core.capability.CapabilityRef;
import java.util.Locale;

/** Capability catalog entry for Flow assembly / NL draft. */
public record CapabilityView(String kind, String name, String ref) {

  public static CapabilityView from(CapabilityRef capability) {
    return new CapabilityView(
        capability.kind().name().toLowerCase(Locale.ROOT),
        capability.name(),
        capability.describe());
  }
}
