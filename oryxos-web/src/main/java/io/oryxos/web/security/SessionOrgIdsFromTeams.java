package io.oryxos.web.security;

import io.oryxos.core.policy.TeamOrgLookup;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** 由 teamIds 经 {@link TeamOrgLookup} 派生 orgIds（#560）。调用方负责 flag 门控；本类不做开关判断。 */
public final class SessionOrgIdsFromTeams {

  private SessionOrgIdsFromTeams() {}

  /** 去重保序；lookup 缺失 / 未知 team / 无 org_id → 跳过。 */
  public static Set<String> resolve(Set<String> teamIds, TeamOrgLookup lookup) {
    if (teamIds == null || teamIds.isEmpty() || lookup == null) {
      return Set.of();
    }
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String teamId : teamIds) {
      if (teamId == null || teamId.isBlank()) {
        continue;
      }
      Optional<String> orgId = lookup.findOrgId(teamId.strip());
      if (orgId.isPresent() && !orgId.get().isBlank()) {
        out.add(orgId.get().strip());
      }
    }
    return out.isEmpty() ? Set.of() : Set.copyOf(out);
  }
}
