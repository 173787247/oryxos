package io.oryxos.web.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GovernanceUnifiedDiffTest {

  @Test
  @DisplayName("identical_只有文件头")
  void identical_headersOnly() {
    String d = GovernanceUnifiedDiff.unified(1, 2, "owner: a\n", "owner: a\n");
    assertThat(d).startsWith("--- a/revision/1\n+++ b/revision/2\n");
    assertThat(d).doesNotContain("@@");
  }

  @Test
  @DisplayName("changed_含加减行")
  void changed_hasPlusMinus() {
    String d =
        GovernanceUnifiedDiff.unified(
            1, 2, "owner: alice\nhealth: ACTIVE\n", "owner: bob\nhealth: ACTIVE\n");
    assertThat(d).contains("-owner: alice");
    assertThat(d).contains("+owner: bob");
    assertThat(d).contains(" health: ACTIVE");
  }
}
