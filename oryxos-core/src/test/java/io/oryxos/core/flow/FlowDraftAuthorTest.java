package io.oryxos.core.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FlowDraftAuthorTest {

  private static final String VALID =
      """
      ---
      apiVersion: oryxos.flow/v1
      kind: Flow
      id: hello-notify
      version: "1.0.0"
      entry: draft
      nodes:
        draft:
          type: agent
          ref: writer
          inputs:
            topic:
              type: string
              required: true
          outputs:
            message:
              type: string
        send:
          type: notify
          inputs:
            text:
              type: string
              from: draft.message
              required: true
          outputs:
            delivered:
              type: boolean
      edges:
        - from: draft
          to: send
      ---

      Minimal linear Flow.
      """;

  @Test
  @DisplayName("stub LLM → valid draft")
  void generatesValid() {
    FlowDraftAuthor author = new FlowDraftAuthor(prompt -> VALID, () -> List.of("writer"));
    FlowDraftAuthor.Draft draft = author.generate("hello-notify", "写一段介绍并发通知");
    assertEquals("hello-notify", draft.flowId());
    assertEquals("draft", draft.definition().entry());
    assertTrue(draft.markdown().contains("apiVersion: oryxos.flow/v1"));
  }

  @Test
  @DisplayName("slugify goal when id omitted")
  void slugifies() {
    assertEquals("ship-it-now", FlowDraftAuthor.slugify("Ship it now!"));
  }

  @Test
  @DisplayName("blank goal rejected")
  void blankGoal() {
    FlowDraftAuthor author = new FlowDraftAuthor(p -> VALID, List::of);
    assertThrows(IllegalArgumentException.class, () -> author.generate("x", "  "));
  }

  @Test
  @DisplayName("invalid LLM output → 400-style")
  void invalidMarkdown() {
    FlowDraftAuthor author = new FlowDraftAuthor(p -> "not a flow", List::of);
    assertThrows(IllegalArgumentException.class, () -> author.generate("bad", "do stuff"));
  }

  @Test
  @DisplayName("rewrites id line to requested id")
  void rewritesId() {
    FlowDraftAuthor author = new FlowDraftAuthor(prompt -> VALID, () -> List.of("writer"));
    FlowDraftAuthor.Draft draft = author.generate("my-flow", "notify after draft");
    assertEquals("my-flow", draft.flowId());
    assertEquals("my-flow", draft.definition().id());
  }
}
