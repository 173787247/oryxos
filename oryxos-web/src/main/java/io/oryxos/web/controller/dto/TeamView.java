package io.oryxos.web.controller.dto;

import io.oryxos.storage.Team;

/** 团队目录视图（#546）：仅 teamId + displayName。 */
public record TeamView(String teamId, String displayName) {

  public static TeamView from(Team team) {
    if (team == null) {
      return new TeamView(null, null);
    }
    return new TeamView(team.getTeamId(), team.getDisplayName());
  }
}
