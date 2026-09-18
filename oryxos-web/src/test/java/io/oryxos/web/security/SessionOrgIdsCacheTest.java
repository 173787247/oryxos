package io.oryxos.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionOrgIdsCacheTest {

  @Test
  void putGetRemove() {
    SessionOrgIdsCache cache = new SessionOrgIdsCache();
    cache.put("s1", List.of(" acme ", "", "beta"));
    assertThat(cache.get("s1")).containsExactly("acme", "beta");
    cache.remove("s1");
    assertThat(cache.get("s1")).isEmpty();
  }

  @Test
  void emptyClears() {
    SessionOrgIdsCache cache = new SessionOrgIdsCache();
    cache.put("s1", List.of("acme"));
    cache.put("s1", List.of());
    assertThat(cache.get("s1")).isEmpty();
  }

  @Test
  void fromTeamsDerivesDistinctOrgs() {
    Set<String> orgs =
        SessionOrgIdsFromTeams.resolve(
            Set.of("ops", "platform", "ghost"),
            teamId -> {
              if ("ops".equals(teamId) || "platform".equals(teamId)) {
                return Optional.of("acme");
              }
              return Optional.empty();
            });
    assertThat(orgs).containsExactly("acme");
  }
}
