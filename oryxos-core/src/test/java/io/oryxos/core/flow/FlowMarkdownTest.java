package io.oryxos.core.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FlowMarkdownTest {

  @Test
  void parse_linearFlow_mapsNodesAndEdges() {
    String md =
        """
        ---
        apiVersion: oryxos.flow/v1
        kind: Flow
        id: demo
        version: "1"
        entry: a
        nodes:
          a:
            type: agent
            ref: writer
            outputs:
              out: { type: string }
          b:
            type: notify
            ref: feishu
            inputs:
              text: { type: string, from: a.out }
        edges:
          - from: a
            to: b
        ---

        # Demo
        """;
    FlowDefinition flow = FlowMarkdown.parse(md);
    assertEquals("demo", flow.id());
    assertEquals("a", flow.entry());
    assertEquals(2, flow.nodes().size());
    assertEquals(FlowNodeType.AGENT, flow.nodes().get("a").type());
    assertEquals("a.out", flow.nodes().get("b").inputs().get("text").from());
    assertEquals(1, flow.edges().size());
    assertTrue(flow.description().contains("Demo"));
  }

  @Test
  void parse_missingFrontmatter_throws() {
    assertThrows(IllegalArgumentException.class, () -> FlowMarkdown.parse("# only body\n"));
  }

  @Test
  void parse_badKind_throws() {
    String md =
        """
        ---
        apiVersion: oryxos.flow/v1
        kind: Pipeline
        id: x
        entry: a
        nodes:
          a: { type: agent }
        ---
        """;
    assertThrows(IllegalArgumentException.class, () -> FlowMarkdown.parse(md));
  }
}
