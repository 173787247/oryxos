package io.oryxos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RunOutputContextTest {
  @Test
  void eachTurnGetsDistinctIdentityAndExceptionRestoresPreviousScope() {
    assertThat(RunOutputContext.current()).isEmpty();
    String first;
    try (var scope = RunOutputContext.open("writer")) {
      first = RunOutputContext.current().orElseThrow().relativeDirectory();
      assertThat(first).startsWith("output/writer/");
      assertThrows(
          IllegalStateException.class,
          () -> {
            try (var nested = RunOutputContext.open("other")) {
              assertThat(RunOutputContext.current().orElseThrow().relativeDirectory())
                  .startsWith("output/other/");
              throw new IllegalStateException("turn failure");
            }
          });
      assertThat(RunOutputContext.current().orElseThrow().relativeDirectory()).isEqualTo(first);
    }
    assertThat(RunOutputContext.current()).isEmpty();
    try (var scope = RunOutputContext.open("writer")) {
      assertThat(RunOutputContext.current().orElseThrow().relativeDirectory()).isNotEqualTo(first);
    }
    assertThat(RunOutputContext.current()).isEmpty();
  }

  @Test
  void outputIdentityRejectsTraversal() {
    assertThrows(IllegalArgumentException.class, () -> RunOutputContext.open("../escape"));
  }
}
