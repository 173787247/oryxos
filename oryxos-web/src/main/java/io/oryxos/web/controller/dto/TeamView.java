package io.oryxos.web.controller.dto;

import io.oryxos.storage.Team;

/** 团队目录视图（#546 / #554）：teamId + displayName + 可选 orgId。 */
public record TeamView(String teamId, String displayName, String orgId) {

  public static TeamView from(Team team) {
    if (team == null) {
      return new TeamView(null, null, null);
    }
    return new TeamView(team.getTeamId(), team.getDisplayName(), team.getOrgId());
  }
}
