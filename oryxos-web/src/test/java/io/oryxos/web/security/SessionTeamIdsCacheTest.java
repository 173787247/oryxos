package io.oryxos.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SessionTeamIdsCacheTest {

  @Test
  void putGetRemove() {
    SessionTeamIdsCache cache = new SessionTeamIdsCache();
    cache.put("s1", List.of(" ops ", "", "platform"));
    assertThat(cache.get("s1")).containsExactly("ops", "platform");
    cache.remove("s1");
    assertThat(cache.get("s1")).isEmpty();
  }

  @Test
  void emptyGroupsClears() {
    SessionTeamIdsCache cache = new SessionTeamIdsCache();
    cache.put("s1", List.of("ops"));
    cache.put("s1", List.of());
    assertThat(cache.get("s1")).isEmpty();
  }
}
