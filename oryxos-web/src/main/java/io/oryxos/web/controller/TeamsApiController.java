package io.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.storage.Organization;
import io.oryxos.storage.OrganizationCatalogService;
import io.oryxos.storage.Team;
import io.oryxos.storage.TeamCatalogService;
import io.oryxos.storage.TeamMembershipService;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.config.WebTeamsApiProperties;
import io.oryxos.web.controller.dto.CreateOrganizationRequest;
import io.oryxos.web.controller.dto.CreateTeamRequest;
import io.oryxos.web.controller.dto.OrganizationView;
import io.oryxos.web.controller.dto.PatchOrganizationRequest;
import io.oryxos.web.controller.dto.PatchTeamRequest;
import io.oryxos.web.controller.dto.SetParentOrgRequest;
import io.oryxos.web.controller.dto.SetParentTeamRequest;
import io.oryxos.web.controller.dto.SetTeamOrgRequest;
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
 * 团队/组织目录与成员 HTTP API（#546 / #554 / #566 / #581）：镜像 CLI {@code oryxos team *} / {@code oryxos org
 * *} → {@link TeamCatalogService} / {@link OrganizationCatalogService} / {@link
 * TeamMembershipService}。
 *
 * <p>flag {@code oryxos.web.teams-api.enabled} 默认关 → 404（含 /api/v1/orgs）。授权走 {@code
 * RequestActionResolver} 的 {@code MANAGE_MEMBERS}（ADMIN），不另开权限路径。
 */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "teams/orgs API 是有意暴露的 Spring Controller（#546/#554）；catalog/memberships/properties 为 Spring"
            + " 注入共享单例，构造注入存同一引用正是意图（镜像既有 Controller 的 SuppressFBWarnings 模式）。")
@RestController
public class TeamsApiController {

  private static final String MSG_DISABLED = "teams api disabled";

  private static final String MSG_TEAM_NOT_FOUND_PREFIX = "team not found: ";

  private static final String MSG_ORG_NOT_FOUND_PREFIX = "org not found: ";

  private static final String MSG_CATALOG_MISSING_PREFIX = "team not in catalog: ";

  private final WebTeamsApiProperties properties;
  private final TeamCatalogService catalog;
  private final OrganizationCatalogService organizations;
  private final TeamMembershipService memberships;

  public TeamsApiController(
      WebTeamsApiProperties properties,
      TeamCatalogService catalog,
      OrganizationCatalogService organizations,
      TeamMembershipService memberships) {
    this.properties = properties;
    this.catalog = catalog;
    this.organizations = organizations;
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

  @PutMapping("/api/v1/teams/{teamId}/org")
  public ApiResponse<TeamView> setTeamOrg(
      @PathVariable String teamId, @RequestBody(required = false) SetTeamOrgRequest body) {
    requireEnabled();
    String orgId = body == null ? null : body.orgId();
    return ApiResponse.ok(TeamView.from(catalog.setOrg(teamId, orgId)));
  }

  @PutMapping("/api/v1/teams/{teamId}/parent")
  public ApiResponse<TeamView> setTeamParent(
      @PathVariable String teamId, @RequestBody(required = false) SetParentTeamRequest body) {
    requireEnabled();
    String parentTeamId = body == null ? null : body.parentTeamId();
    return ApiResponse.ok(TeamView.from(catalog.setParent(teamId, parentTeamId)));
  }

  @DeleteMapping("/api/v1/teams/{teamId}")
  public ApiResponse<Void> deleteTeam(@PathVariable String teamId) {
    requireEnabled();
    catalog.delete(teamId);
    return ApiResponse.ok(null);
  }

  @GetMapping("/api/v1/orgs")
  public ApiResponse<List<OrganizationView>> listOrgs() {
    requireEnabled();
    List<OrganizationView> views = new ArrayList<>();
    for (Organization org : organizations.list()) {
      views.add(OrganizationView.from(org));
    }
    return ApiResponse.ok(List.copyOf(views));
  }

  @PostMapping("/api/v1/orgs")
  public ApiResponse<OrganizationView> createOrg(@RequestBody CreateOrganizationRequest body) {
    requireEnabled();
    String orgId = body == null ? null : body.orgId();
    String displayName = body == null ? null : body.displayName();
    return ApiResponse.ok(OrganizationView.from(organizations.create(orgId, displayName)));
  }

  @GetMapping("/api/v1/orgs/{orgId}")
  public ApiResponse<OrganizationView> getOrg(@PathVariable String orgId) {
    requireEnabled();
    Organization org =
        organizations
            .find(orgId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_ORG_NOT_FOUND_PREFIX + orgId));
    return ApiResponse.ok(OrganizationView.from(org));
  }

  @PatchMapping("/api/v1/orgs/{orgId}")
  public ApiResponse<OrganizationView> patchOrg(
      @PathVariable String orgId, @RequestBody PatchOrganizationRequest body) {
    requireEnabled();
    String displayName = body == null ? null : body.displayName();
    return ApiResponse.ok(OrganizationView.from(organizations.rename(orgId, displayName)));
  }

  @PutMapping("/api/v1/orgs/{orgId}/parent")
  public ApiResponse<OrganizationView> setOrgParent(
      @PathVariable String orgId, @RequestBody(required = false) SetParentOrgRequest body) {
    requireEnabled();
    String parentOrgId = body == null ? null : body.parentOrgId();
    return ApiResponse.ok(OrganizationView.from(organizations.setParent(orgId, parentOrgId)));
  }

  @DeleteMapping("/api/v1/orgs/{orgId}")
  public ApiResponse<Void> deleteOrg(@PathVariable String orgId) {
    requireEnabled();
    organizations.delete(orgId);
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
