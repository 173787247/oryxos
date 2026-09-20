package io.oryxos.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.web.config.WebRbacProperties;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** #560：flag 默认关 → 不写 orgIds；开 → 由 team org_id 派生。 */
class OrgIdsFromTeamOrgFlagTest {

  @Test
  void flagOffLeavesCacheEmpty() {
    WebRbacProperties rbac = new WebRbacProperties();
    assertThat(rbac.isOrgIdsFromTeamOrgEnabled()).isFalse();
    SessionOrgIdsCache cache = new SessionOrgIdsCache();
    fillIfEnabled(rbac, cache, "s1", Set.of("ops"));
    assertThat(cache.get("s1")).isEmpty();
  }

  @Test
  void flagOnCachesDerivedOrgIds() {
    WebRbacProperties rbac = new WebRbacProperties();
    rbac.setOrgIdsFromTeamOrgEnabled(true);
    SessionOrgIdsCache cache = new SessionOrgIdsCache();
    fillIfEnabled(rbac, cache, "s1", Set.of("ops"));
    assertThat(cache.get("s1")).containsExactly("acme");
  }

  private static void fillIfEnabled(
      WebRbacProperties rbac, SessionOrgIdsCache cache, String sid, Set<String> teams) {
    if (!rbac.isOrgIdsFromTeamOrgEnabled()) {
      cache.remove(sid);
      return;
    }
    cache.put(
        sid,
        SessionOrgIdsFromTeams.resolve(
            teams, teamId -> "ops".equals(teamId) ? Optional.of("acme") : Optional.empty()));
  }
}
