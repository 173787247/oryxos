package io.oryxos.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PrincipalContextTest {

  @AfterEach
  void tearDown() {
    PrincipalContext.clear();
  }

  @Test
  void setClearRoundTrip() {
    assertThat(PrincipalContext.current()).isNull();
    Principal user = Principal.user("alice", "alice", Set.of(Role.EDITOR));
    PrincipalContext.set(user);
    assertThat(PrincipalContext.current()).isSameAs(user);
    PrincipalContext.clear();
    assertThat(PrincipalContext.current()).isNull();
  }

  @Test
  void clearAfterExceptionDoesNotLeak() {
    PrincipalContext.set(Principal.user("bob", "bob", Set.of()));
    try {
      throw new IllegalStateException("boom");
    } catch (IllegalStateException ignored) {
      PrincipalContext.clear();
    }
    assertThat(PrincipalContext.current()).isNull();
  }
}
