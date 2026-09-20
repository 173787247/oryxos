package io.oryxos.web.controller.dto;

import io.oryxos.storage.Team;

/** 团队目录视图（#546 / #554 / #581）：teamId + displayName + 可选 orgId + 可选 parentTeamId。 */
public record TeamView(String teamId, String displayName, String orgId, String parentTeamId) {

  public static TeamView from(Team team) {
    if (team == null) {
      return new TeamView(null, null, null, null);
    }
    return new TeamView(
        team.getTeamId(), team.getDisplayName(), team.getOrgId(), team.getParentTeamId());
  }
}
