package io.oryxos.web.controller.dto;

import java.util.List;

/** 用户团队成员列表（#546）：有序 teamId 列表。 */
public record UserTeamsView(String username, List<String> teamIds) {

  public UserTeamsView {
    teamIds = teamIds == null ? List.of() : List.copyOf(teamIds);
  }
}
