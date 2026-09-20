package io.oryxos.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HighRiskActionClassifierTest {

  private final HighRiskActionClassifier classifier =
      new HighRiskActionClassifier(name -> "x".equals(name) ? "srv" : null);

  @Test
  @DisplayName("内置工具分类")
  void builtins() {
    assertThat(classifier.classify("shell")).contains(HighRiskActionType.SHELL);
    assertThat(classifier.classify("notify")).contains(HighRiskActionType.EXTERNAL_SEND);
    assertThat(classifier.classify("write_file")).contains(HighRiskActionType.FILE_MUTATION);
    assertThat(classifier.classify("read_file")).isEmpty();
  }

  @Test
  @DisplayName("MCP 归属 → MCP")
  void mcp() {
    assertThat(classifier.classify("x")).contains(HighRiskActionType.MCP);
  }
}
