package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.storage.Team;
import io.oryxos.storage.TeamCatalogService;
import io.oryxos.storage.TeamMembershipService;
import io.oryxos.web.GlobalExceptionHandler;
import io.oryxos.web.config.WebTeamsApiProperties;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Teams HTTP API（#546）：flag 关 404；开时 list/create/member 走 catalog + memberships。 */
class TeamsApiControllerTest {

  private MockMvc mvc;
  private WebTeamsApiProperties properties;
  private TeamCatalogService catalog;
  private TeamMembershipService memberships;

  @BeforeEach
  void setUp() {
    properties = new WebTeamsApiProperties();
    catalog = mock(TeamCatalogService.class);
    memberships = mock(TeamMembershipService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new TeamsApiController(properties, catalog, memberships))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("flag关_list_返回404")
  void flagOff_list_404() throws Exception {
    properties.setEnabled(false);
    mvc.perform(get("/api/v1/teams")).andExpect(status().isNotFound());
    verify(catalog, never()).list();
  }

  @Test
  @DisplayName("flag开_list_返回目录")
  void flagOn_list() throws Exception {
    properties.setEnabled(true);
    Team t = new Team();
    t.setTeamId("eng");
    t.setDisplayName("Engineering");
    when(catalog.list()).thenReturn(List.of(t));
    mvc.perform(get("/api/v1/teams"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].teamId").value("eng"))
        .andExpect(jsonPath("$.data[0].displayName").value("Engineering"));
  }

  @Test
  @DisplayName("flag开_create_调用catalog")
  void flagOn_create() throws Exception {
    properties.setEnabled(true);
    Team t = new Team();
    t.setTeamId("eng");
    t.setDisplayName("Engineering");
    when(catalog.create(eq("eng"), eq("Engineering"))).thenReturn(t);
    mvc.perform(
            post("/api/v1/teams")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\":\"eng\",\"displayName\":\"Engineering\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.teamId").value("eng"));
    verify(catalog).create("eng", "Engineering");
  }

  @Test
  @DisplayName("flag开_get缺失_404")
  void flagOn_getMissing_404() throws Exception {
    properties.setEnabled(true);
    when(catalog.find("missing")).thenReturn(Optional.empty());
    mvc.perform(get("/api/v1/teams/missing")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("flag开_patch_rename")
  void flagOn_patch() throws Exception {
    properties.setEnabled(true);
    Team t = new Team();
    t.setTeamId("eng");
    t.setDisplayName("Eng2");
    when(catalog.rename(eq("eng"), eq("Eng2"))).thenReturn(t);
    mvc.perform(
            patch("/api/v1/teams/eng")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Eng2\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.displayName").value("Eng2"));
  }

  @Test
  @DisplayName("flag开_delete_调用catalog")
  void flagOn_delete() throws Exception {
    properties.setEnabled(true);
    mvc.perform(delete("/api/v1/teams/eng")).andExpect(status().isOk());
    verify(catalog).delete("eng");
  }

  @Test
  @DisplayName("flag开_memberList")
  void flagOn_memberList() throws Exception {
    properties.setEnabled(true);
    when(memberships.listTeamIds("alice")).thenReturn(Set.of("eng"));
    mvc.perform(get("/api/v1/users/alice/teams"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("alice"))
        .andExpect(jsonPath("$.data.teamIds[0]").value("eng"));
  }

  @Test
  @DisplayName("flag开_memberAdd_目录存在")
  void flagOn_memberAdd() throws Exception {
    properties.setEnabled(true);
    Team t = new Team();
    t.setTeamId("eng");
    t.setDisplayName("Engineering");
    when(catalog.find("eng")).thenReturn(Optional.of(t));
    when(memberships.listTeamIds("alice")).thenReturn(Set.of("eng"));
    mvc.perform(put("/api/v1/users/alice/teams/eng"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.teamIds[0]").value("eng"));
    verify(memberships).add("alice", "eng");
  }

  @Test
  @DisplayName("flag开_memberAdd_目录缺失_400")
  void flagOn_memberAdd_catalogMissing_400() throws Exception {
    properties.setEnabled(true);
    when(catalog.find("ghost")).thenReturn(Optional.empty());
    mvc.perform(put("/api/v1/users/alice/teams/ghost")).andExpect(status().isBadRequest());
    verify(memberships, never()).add(eq("alice"), eq("ghost"));
  }

  @Test
  @DisplayName("flag开_memberRemove")
  void flagOn_memberRemove() throws Exception {
    properties.setEnabled(true);
    when(memberships.listTeamIds("alice")).thenReturn(Set.of());
    mvc.perform(delete("/api/v1/users/alice/teams/eng"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.teamIds").isEmpty());
    verify(memberships).remove("alice", "eng");
  }

  @Test
  @DisplayName("flag关_member_404")
  void flagOff_member_404() throws Exception {
    properties.setEnabled(false);
    mvc.perform(get("/api/v1/users/alice/teams")).andExpect(status().isNotFound());
    verify(memberships, never()).listTeamIds(eq("alice"));
  }
}
