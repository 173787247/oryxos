package io.oryxos.core.flow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.capability.CapabilityRef;
import io.oryxos.core.capability.InMemoryCapabilityCatalog;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FlowCapabilityValidationTest {

  private static final String MD =
      """
      ---
      apiVersion: oryxos.flow/v1
      kind: Flow
      id: cap-demo
      version: "1.0.0"
      entry: draft
      nodes:
        draft:
          type: agent
          ref: writer
          capabilities:
            - skill:summarizer
            - knowledge:ops-manual
            - tool:web_search
          inputs:
            topic:
              type: string
              required: true
          outputs:
            message:
              type: string
      edges: []
      ---

      Capability refs demo.
      """;

  @Test
  @DisplayName("parse capabilities from markdown")
  void parsesCapabilities() {
    FlowDefinition def = FlowMarkdown.parse(MD);
    List<CapabilityRef> caps = def.nodes().get("draft").capabilities();
    assertTrue(caps.stream().anyMatch(c -> c.describe().equals("skill:summarizer")));
    assertTrue(caps.stream().anyMatch(c -> c.describe().equals("knowledge:ops-manual")));
    assertTrue(caps.stream().anyMatch(c -> c.describe().equals("tool:web_search")));
  }

  @Test
  @DisplayName("catalog miss → UNKNOWN_CAPABILITY")
  void unknownCapability() {
    InMemoryCapabilityCatalog catalog =
        new InMemoryCapabilityCatalog(
            List.of(CapabilityRef.skill("summarizer"), CapabilityRef.tool("web_search")));
    FlowDocuments.Result r = FlowDocuments.parseAndValidate(MD, catalog);
    assertFalse(r.ok());
    assertTrue(
        r.diagnostics().stream()
            .anyMatch(
                d ->
                    d.isError()
                        && "UNKNOWN_CAPABILITY".equals(d.code())
                        && d.message().contains("knowledge:ops-manual")));
  }

  @Test
  @DisplayName("full catalog → ok")
  void knownCapabilities() {
    InMemoryCapabilityCatalog catalog =
        new InMemoryCapabilityCatalog(
            List.of(
                CapabilityRef.skill("summarizer"),
                CapabilityRef.knowledge("ops-manual"),
                CapabilityRef.tool("web_search")));
    FlowDocuments.Result r = FlowDocuments.parseAndValidate(MD, catalog);
    assertTrue(r.ok());
  }

  @Test
  @DisplayName("null catalog skips capability checks")
  void nullCatalogSkips() {
    FlowDocuments.Result r = FlowDocuments.parseAndValidate(MD, null);
    assertTrue(r.ok());
  }
}
