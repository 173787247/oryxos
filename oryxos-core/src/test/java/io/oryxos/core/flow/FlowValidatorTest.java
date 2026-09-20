package io.oryxos.core.flow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FlowValidatorTest {

  @Test
  void validLinear_noErrors() {
    FlowDefinition flow =
        FlowMarkdown.parse(
            """
            ---
            apiVersion: oryxos.flow/v1
            id: ok
            entry: a
            nodes:
              a:
                type: agent
                outputs: { out: { type: string } }
              b:
                type: notify
                inputs: { text: { type: string, from: a.out } }
            edges:
              - from: a
                to: b
            ---
            """);
    List<FlowDiagnostic> diags = FlowValidator.validate(flow);
    assertFalse(FlowValidator.hasErrors(diags), diags::toString);
  }

  @Test
  void unknownEdgeTarget_reports() {
    FlowDefinition flow =
        FlowMarkdown.parse(
            """
            ---
            apiVersion: oryxos.flow/v1
            id: bad-edge
            entry: a
            nodes:
              a: { type: agent }
            edges:
              - from: a
                to: missing
            ---
            """);
    assertTrue(hasCode(FlowValidator.validate(flow), "UNKNOWN_NODE_REF"));
  }

  @Test
  void typeMismatch_reports() {
    FlowDefinition flow =
        FlowMarkdown.parse(
            """
            ---
            apiVersion: oryxos.flow/v1
            id: bad-type
            entry: a
            nodes:
              a:
                type: agent
                outputs: { out: { type: string } }
              b:
                type: tool
                inputs: { n: { type: number, from: a.out } }
            edges:
              - from: a
                to: b
            ---
            """);
    assertTrue(hasCode(FlowValidator.validate(flow), "TYPE_MISMATCH"));
  }

  @Test
  void cycle_reports() {
    FlowDefinition flow =
        FlowMarkdown.parse(
            """
            ---
            apiVersion: oryxos.flow/v1
            id: cyclic
            entry: a
            nodes:
              a:
                type: agent
                dependsOn: [b]
                outputs: { out: { type: string } }
              b:
                type: agent
                dependsOn: [a]
                outputs: { out: { type: string } }
            ---
            """);
    assertTrue(hasCode(FlowValidator.validate(flow), "CYCLE_DETECTED"));
  }

  @Test
  void missingWirePort_reports() {
    FlowDefinition flow =
        FlowMarkdown.parse(
            """
            ---
            apiVersion: oryxos.flow/v1
            id: bad-port
            entry: a
            nodes:
              a:
                type: agent
                outputs: { out: { type: string } }
              b:
                type: notify
                inputs: { text: { type: string, from: a.missing } }
            ---
            """);
    assertTrue(hasCode(FlowValidator.validate(flow), "UNKNOWN_PORT_REF"));
  }

  private static boolean hasCode(List<FlowDiagnostic> diags, String code) {
    return diags.stream().anyMatch(d -> code.equals(d.code()) && d.isError());
  }
}
