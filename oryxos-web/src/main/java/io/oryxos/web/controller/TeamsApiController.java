package io.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.storage.Team;
import io.oryxos.storage.TeamCatalogService;
import io.oryxos.storage.TeamMembershipService;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.config.WebTeamsApiProperties;
import io.oryxos.web.controller.dto.CreateTeamRequest;
import io.oryxos.web.controller.dto.PatchTeamRequest;
import io.oryxos.web.controller.dto.TeamView;
import io.oryxos.web.controller.dto.UserTeamsView;
import io.oryxos.web.error.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 团队目录与成员 HTTP API（#546）：镜像 CLI {@code oryxos team *} → {@link TeamCatalogService} / {@link
 * TeamMembershipService}。
 *
 * <p>flag {@code oryxos.web.teams-api.enabled} 默认关 → 404。授权走 {@code RequestActionResolver} 的 {@code
 * MANAGE_MEMBERS}（ADMIN），不另开权限路径。
 */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "teams API 是有意暴露的 Spring Controller（#546）；catalog/memberships/properties 为 Spring"
            + " 注入共享单例，构造注入存同一引用正是意图（镜像既有 Controller 的 SuppressFBWarnings 模式）。")
@RestController
public class TeamsApiController {

  private static final String MSG_DISABLED = "teams api disabled";

  private static final String MSG_TEAM_NOT_FOUND_PREFIX = "team not found: ";

  private static final String MSG_CATALOG_MISSING_PREFIX = "team not in catalog: ";

  private final WebTeamsApiProperties properties;
  private final TeamCatalogService catalog;
  private final TeamMembershipService memberships;

  public TeamsApiController(
      WebTeamsApiProperties properties,
      TeamCatalogService catalog,
      TeamMembershipService memberships) {
    this.properties = properties;
    this.catalog = catalog;
    this.memberships = memberships;
  }

  @GetMapping("/api/v1/teams")
  public ApiResponse<List<TeamView>> listTeams() {
    requireEnabled();
    List<TeamView> views = new ArrayList<>();
    for (Team team : catalog.list()) {
      views.add(TeamView.from(team));
    }
    return ApiResponse.ok(List.copyOf(views));
  }

  @PostMapping("/api/v1/teams")
  public ApiResponse<TeamView> createTeam(@RequestBody CreateTeamRequest body) {
    requireEnabled();
    String teamId = body == null ? null : body.teamId();
    String displayName = body == null ? null : body.displayName();
    return ApiResponse.ok(TeamView.from(catalog.create(teamId, displayName)));
  }

  @GetMapping("/api/v1/teams/{teamId}")
  public ApiResponse<TeamView> getTeam(@PathVariable String teamId) {
    requireEnabled();
    Team team =
        catalog
            .find(teamId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_TEAM_NOT_FOUND_PREFIX + teamId));
    return ApiResponse.ok(TeamView.from(team));
  }

  @PatchMapping("/api/v1/teams/{teamId}")
  public ApiResponse<TeamView> patchTeam(
      @PathVariable String teamId, @RequestBody PatchTeamRequest body) {
    requireEnabled();
    String displayName = body == null ? null : body.displayName();
    return ApiResponse.ok(TeamView.from(catalog.rename(teamId, displayName)));
  }

  @DeleteMapping("/api/v1/teams/{teamId}")
  public ApiResponse<Void> deleteTeam(@PathVariable String teamId) {
    requireEnabled();
    catalog.delete(teamId);
    return ApiResponse.ok(null);
  }

  @GetMapping("/api/v1/users/{username}/teams")
  public ApiResponse<UserTeamsView> listUserTeams(@PathVariable String username) {
    requireEnabled();
    List<String> ids = new ArrayList<>(memberships.listTeamIds(username));
    return ApiResponse.ok(new UserTeamsView(username, ids));
  }

  @PutMapping("/api/v1/users/{username}/teams/{teamId}")
  public ApiResponse<UserTeamsView> addUserTeam(
      @PathVariable String username, @PathVariable String teamId) {
    requireEnabled();
    requireCatalogTeam(teamId);
    memberships.add(username, teamId);
    List<String> ids = new ArrayList<>(memberships.listTeamIds(username));
    return ApiResponse.ok(new UserTeamsView(username, ids));
  }

  @DeleteMapping("/api/v1/users/{username}/teams/{teamId}")
  public ApiResponse<UserTeamsView> removeUserTeam(
      @PathVariable String username, @PathVariable String teamId) {
    requireEnabled();
    memberships.remove(username, teamId);
    List<String> ids = new ArrayList<>(memberships.listTeamIds(username));
    return ApiResponse.ok(new UserTeamsView(username, ids));
  }

  private void requireEnabled() {
    if (properties == null || !properties.isEnabled()) {
      throw new ResourceNotFoundException(MSG_DISABLED);
    }
  }

  private void requireCatalogTeam(String teamId) {
    if (catalog.find(teamId).isEmpty()) {
      throw new IllegalArgumentException(MSG_CATALOG_MISSING_PREFIX + teamId);
    }
  }
}
