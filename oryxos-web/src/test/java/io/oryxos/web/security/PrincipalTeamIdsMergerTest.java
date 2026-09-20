package io.oryxos.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.storage.TeamMembershipService;
import io.oryxos.web.config.WebRbacProperties;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PrincipalTeamIdsMergerTest {

  @Test
  @DisplayName("flag关_不读库_仅session")
  void flagOff_sessionOnly() {
    WebRbacProperties rbac = new WebRbacProperties();
    TeamMembershipService memberships = mock(TeamMembershipService.class);
    PrincipalTeamIdsMerger merger = new PrincipalTeamIdsMerger(rbac, memberships);

    assertThat(merger.merge("alice", Set.of("eng"))).containsExactly("eng");
    verify(memberships, never()).listTeamIds(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  @DisplayName("flag开_session与持久化取并集")
  void flagOn_unionsDurable() {
    WebRbacProperties rbac = new WebRbacProperties();
    rbac.setDurableTeamMembershipsEnabled(true);
    TeamMembershipService memberships = mock(TeamMembershipService.class);
    when(memberships.listTeamIds("alice")).thenReturn(Set.of("platform", "eng"));
    PrincipalTeamIdsMerger merger = new PrincipalTeamIdsMerger(rbac, memberships);

    assertThat(merger.merge("alice", Set.of("eng", "ops")))
        .containsExactlyInAnyOrder("eng", "ops", "platform");
  }
}
