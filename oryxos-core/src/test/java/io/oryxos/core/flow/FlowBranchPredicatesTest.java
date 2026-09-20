package io.oryxos.core.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowBranchPredicatesTest {

  @Test
  void equalsAndNotEquals() {
    Map<String, Object> outs = Map.of("decision", "approved");
    assertThat(FlowBranchPredicates.matches("decision == approved", Map.of(), outs)).isTrue();
    assertThat(FlowBranchPredicates.matches("decision == denied", Map.of(), outs)).isFalse();
    assertThat(FlowBranchPredicates.matches("decision != denied", Map.of(), outs)).isTrue();
    assertThat(FlowBranchPredicates.matches(null, Map.of(), outs)).isTrue();
  }
}
