package io.oryxos.web.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.auth.Role;
import io.oryxos.core.policy.AuthorizationService;
import io.oryxos.storage.AuthEventRecorder;
import io.oryxos.storage.AuthEventType;
import io.oryxos.storage.IdentityMapping;
import io.oryxos.storage.IdentityMappingService;
import io.oryxos.storage.Team;
import io.oryxos.storage.TeamCatalogService;
import io.oryxos.storage.TeamMembershipService;
import io.oryxos.storage.WebSession;
import io.oryxos.storage.WebSessionService;
import io.oryxos.storage.WebUserService;
import io.oryxos.web.GlobalExceptionHandler;
import io.oryxos.web.config.WebOidcProperties;
import io.oryxos.web.oidc.OidcAuthService.OidcLoginResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 040 OIDC first cut：flag / PKCE state / 映射 / session / 禁止 AuthorizationService 交互。 */
class OidcAuthServiceTest {

  private WebOidcProperties properties;
  private OidcTokenClient tokenClient;
  private OidcPendingStore pendingStore;
  private IdentityMappingService mappingService;
  private WebUserService userService;
  private WebSessionService sessionService;
  private AuthEventRecorder authEventRecorder;
  private TeamCatalogService teamCatalogService;
  private TeamMembershipService teamMembershipService;
  private AuthorizationService authorizationService;
  private OidcAuthService service;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    properties = new WebOidcProperties();
    properties.setEnabled(true);
    properties.setIssuer("https://idp.example");
    properties.setClientId("oryxos");
    properties.setRedirectUri("https://app.example/api/v1/auth/oidc/callback");
    properties.setAuthorizationEndpoint("https://idp.example/authorize");
    tokenClient = mock(OidcTokenClient.class);
    pendingStore = new OidcPendingStore(java.time.Duration.ofMinutes(10));
    mappingService = mock(IdentityMappingService.class);
    userService = mock(WebUserService.class);
    sessionService = mock(WebSessionService.class);
    authEventRecorder = mock(AuthEventRecorder.class);
    teamCatalogService = mock(TeamCatalogService.class);
    teamMembershipService = mock(TeamMembershipService.class);
    authorizationService = mock(AuthorizationService.class);
    service =
        new OidcAuthService(
            properties,
            tokenClient,
            pendingStore,
            mappingService,
            userService,
            sessionService,
            authEventRecorder,
            null,
            teamCatalogService,
            null,
            null,
            null,
            teamMembershipService);
    mvc =
        MockMvcBuilders.standaloneSetup(new OidcAuthController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    when(tokenClient.resolveAuthorizationEndpoint(any()))
        .thenReturn("https://idp.example/authorize");
  }

  @Test
  @DisplayName("flag关闭_login返回404")
  void login_disabled_returns404() throws Exception {
    properties.setEnabled(false);
    mvc.perform(get("/api/v1/auth/oidc/login")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("state不匹配_失败且不建session")
  void callback_stateMismatch_fails() {
    OidcLoginResult result = service.completeLogin("code", "unknown-state");
    assertThat(result.isSuccess()).isFalse();
    verify(sessionService, never()).create(anyString());
    verify(authEventRecorder)
        .recordBestEffort(eq(AuthEventType.LOGIN_FAILURE), any(), eq("invalid_or_expired_state"));
  }

  @Test
  @DisplayName("未映射subject_无session+LOGIN_FAILURE")
  void callback_unmapped_noSession() {
    pendingStore.put("st", "verifier");
    when(tokenClient.exchangeAndValidate(eq("code"), eq("verifier"), any()))
        .thenReturn(new OidcIdTokenClaims("https://idp.example", "sub-1", null));
    when(mappingService.findByIssuerAndSubject("https://idp.example", "sub-1"))
        .thenReturn(Optional.empty());

    OidcLoginResult result = service.completeLogin("code", "st");
    assertThat(result.isSuccess()).isFalse();
    verify(sessionService, never()).create(anyString());
    verify(authEventRecorder)
        .recordBestEffort(eq(AuthEventType.LOGIN_FAILURE), eq("sub-1"), eq("unmapped_subject"));
  }

  @Test
  @DisplayName("已映射启用用户_建session+LOGIN_SUCCESS")
  void callback_mappedEnabled_createsSession() throws Exception {
    pendingStore.put("st", "verifier");
    when(tokenClient.exchangeAndValidate(eq("code"), eq("verifier"), any()))
        .thenReturn(new OidcIdTokenClaims("https://idp.example", "sub-1", "a@b.c"));
    IdentityMapping mapping = new IdentityMapping();
    mapping.setIssuer("https://idp.example");
    mapping.setSubject("sub-1");
    mapping.setUsername("alice");
    when(mappingService.findByIssuerAndSubject("https://idp.example", "sub-1"))
        .thenReturn(Optional.of(mapping));
    when(userService.isEnabledUser("alice")).thenReturn(true);
    WebSession session = new WebSession();
    session.setSessionId("sid-oidc");
    session.setUsername("alice");
    session.setExpiresAt(Instant.now().plusSeconds(3600));
    when(sessionService.create("alice")).thenReturn(session);

    mvc.perform(
            get("/api/v1/auth/oidc/callback")
                .param("code", "code")
                .param("state", "st")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("alice"))
        .andExpect(
            header()
                .string(
                    "Set-Cookie", org.hamcrest.Matchers.containsString("oryxos_session=sid-oidc")));

    verify(sessionService).create("alice");
    verify(authEventRecorder)
        .recordOrThrow(eq(AuthEventType.LOGIN_SUCCESS), eq("alice"), anyString());
    verify(userService, never()).setRoles(anyString(), any());
    verifyNoInteractions(authorizationService);
  }

  @Test
  @DisplayName("callback路径_零交互AuthorizationService")
  void callback_neverCallsAuthorizationService() {
    pendingStore.put("st", "verifier");
    when(tokenClient.exchangeAndValidate(anyString(), anyString(), any()))
        .thenReturn(new OidcIdTokenClaims("https://idp.example", "sub-1", null));
    IdentityMapping mapping = new IdentityMapping();
    mapping.setUsername("alice");
    when(mappingService.findByIssuerAndSubject(anyString(), anyString()))
        .thenReturn(Optional.of(mapping));
    when(userService.isEnabledUser("alice")).thenReturn(true);
    WebSession session = new WebSession();
    session.setSessionId("sid");
    session.setUsername("alice");
    when(sessionService.create("alice")).thenReturn(session);

    assertThat(service.completeLogin("code", "st").isSuccess()).isTrue();
    verifyNoInteractions(authorizationService);
    verify(authorizationService, never()).decide(any(), any(), any());
    verify(userService, never()).setRoles(anyString(), any());
  }

  @Test
  @DisplayName("命中组映射时写角色且仍不调用AuthorizationService")
  void callback_matchingGroup_writesRolesWithoutDecide() {
    pendingStore.put("st", "verifier");
    properties.setGroupRoles(Map.of("oryxos-editors", "EDITOR"));
    when(tokenClient.exchangeAndValidate(anyString(), anyString(), any()))
        .thenReturn(
            new OidcIdTokenClaims("https://idp.example", "sub-1", null, List.of("oryxos-editors")));
    IdentityMapping mapping = new IdentityMapping();
    mapping.setUsername("alice");
    when(mappingService.findByIssuerAndSubject(anyString(), anyString()))
        .thenReturn(Optional.of(mapping));
    when(userService.isEnabledUser("alice")).thenReturn(true);
    WebSession session = new WebSession();
    session.setSessionId("sid");
    session.setUsername("alice");
    when(sessionService.create("alice")).thenReturn(session);

    assertThat(service.completeLogin("code", "st").isSuccess()).isTrue();
    verify(userService).setRoles("alice", Set.of(Role.EDITOR));
    verify(authEventRecorder)
        .recordOrThrow(eq(AuthEventType.GROUP_ROLE_SYNC), eq("alice"), eq("roles=[EDITOR]"));
    verifyNoInteractions(authorizationService);
  }

  @Test
  @DisplayName("JIT开_未映射时建用户映射并建session")
  void callback_jitProvision_createsUserMappingAndSession() {
    pendingStore.put("st", "verifier");
    properties.setJitProvisionEnabled(true);
    when(tokenClient.exchangeAndValidate(anyString(), anyString(), any()))
        .thenReturn(
            new OidcIdTokenClaims(
                "https://idp.example", "sub-new", "alice@example.com", "alice", List.of()));
    when(mappingService.findByIssuerAndSubject("https://idp.example", "sub-new"))
        .thenReturn(Optional.empty());
    IdentityMapping created = new IdentityMapping();
    created.setUsername("alice");
    when(mappingService.upsert("https://idp.example", "sub-new", "alice", "alice@example.com"))
        .thenReturn(created);
    when(userService.isEnabledUser("alice")).thenReturn(true);
    WebSession session = new WebSession();
    session.setSessionId("sid-jit");
    session.setUsername("alice");
    when(sessionService.create("alice")).thenReturn(session);

    assertThat(service.completeLogin("code", "st").isSuccess()).isTrue();
    verify(userService).ensureOidcProvisioned("alice");
    verify(mappingService).upsert("https://idp.example", "sub-new", "alice", "alice@example.com");
    verify(sessionService).create("alice");
    verifyNoInteractions(authorizationService);
  }

  @Test
  @DisplayName("revoke开_组未命中时清空角色")
  void callback_revokeUnmatched_clearsRoles() {
    pendingStore.put("st", "verifier");
    properties.setGroupRoles(Map.of("oryxos-editors", "EDITOR"));
    properties.setRevokeUnmatchedRoles(true);
    when(tokenClient.exchangeAndValidate(anyString(), anyString(), any()))
        .thenReturn(
            new OidcIdTokenClaims("https://idp.example", "sub-1", null, List.of("unknown")));
    IdentityMapping mapping = new IdentityMapping();
    mapping.setUsername("alice");
    when(mappingService.findByIssuerAndSubject(anyString(), anyString()))
        .thenReturn(Optional.of(mapping));
    when(userService.isEnabledUser("alice")).thenReturn(true);
    WebSession session = new WebSession();
    session.setSessionId("sid");
    session.setUsername("alice");
    when(sessionService.create("alice")).thenReturn(session);

    assertThat(service.completeLogin("code", "st").isSuccess()).isTrue();
    verify(userService).setRoles("alice", Set.of());
    verify(authEventRecorder)
        .recordOrThrow(eq(AuthEventType.GROUP_ROLE_SYNC), eq("alice"), eq("roles=[]"));
  }

  @Test
  @DisplayName("JIT team catalog关_不调用ensure")
  void callback_jitTeamCatalogOff_noCatalogCalls() {
    pendingStore.put("st", "verifier");
    properties.setJitTeamCatalogEnabled(false);
    when(tokenClient.exchangeAndValidate(anyString(), anyString(), any()))
        .thenReturn(
            new OidcIdTokenClaims("https://idp.example", "sub-1", null, List.of("eng", "ops")));
    IdentityMapping mapping = new IdentityMapping();
    mapping.setUsername("alice");
    when(mappingService.findByIssuerAndSubject(anyString(), anyString()))
        .thenReturn(Optional.of(mapping));
    when(userService.isEnabledUser("alice")).thenReturn(true);
    WebSession session = new WebSession();
    session.setSessionId("sid");
    session.setUsername("alice");
    when(sessionService.create("alice")).thenReturn(session);

    assertThat(service.completeLogin("code", "st").isSuccess()).isTrue();
    verify(teamCatalogService, never()).ensure(anyString());
    verify(teamCatalogService, never()).ensure(anyString(), any());
  }

  @Test
  @DisplayName("JIT team catalog开_对缺失组ensure_已有跳过由服务幂等")
  void callback_jitTeamCatalogOn_ensuresGroups() {
    pendingStore.put("st", "verifier");
    properties.setJitTeamCatalogEnabled(true);
    when(tokenClient.exchangeAndValidate(anyString(), anyString(), any()))
        .thenReturn(
            new OidcIdTokenClaims(
                "https://idp.example", "sub-1", null, List.of("eng", "ops", "eng")));
    IdentityMapping mapping = new IdentityMapping();
    mapping.setUsername("alice");
    when(mappingService.findByIssuerAndSubject(anyString(), anyString()))
        .thenReturn(Optional.of(mapping));
    when(userService.isEnabledUser("alice")).thenReturn(true);
    WebSession session = new WebSession();
    session.setSessionId("sid");
    session.setUsername("alice");
    when(sessionService.create("alice")).thenReturn(session);

    assertThat(service.completeLogin("code", "st").isSuccess()).isTrue();
    verify(teamCatalogService).ensure("eng");
    verify(teamCatalogService).ensure("ops");
  }

  @Test
  @DisplayName("JIT team memberships关_不调用add")
  void callback_jitTeamMembershipsOff_noAdds() {
    pendingStore.put("st", "verifier");
    properties.setJitTeamMembershipsEnabled(false);
    when(tokenClient.exchangeAndValidate(anyString(), anyString(), any()))
        .thenReturn(
            new OidcIdTokenClaims("https://idp.example", "sub-1", null, List.of("eng", "ops")));
    IdentityMapping mapping = new IdentityMapping();
    mapping.setUsername("alice");
    when(mappingService.findByIssuerAndSubject(anyString(), anyString()))
        .thenReturn(Optional.of(mapping));
    when(userService.isEnabledUser("alice")).thenReturn(true);
    WebSession session = new WebSession();
    session.setSessionId("sid");
    session.setUsername("alice");
    when(sessionService.create("alice")).thenReturn(session);

    assertThat(service.completeLogin("code", "st").isSuccess()).isTrue();
    verify(teamMembershipService, never()).add(anyString(), anyString());
  }

  @Test
  @DisplayName("JIT team memberships开_对每组幂等add")
  void callback_jitTeamMembershipsOn_addsEachGroup() {
    pendingStore.put("st", "verifier");
    properties.setJitTeamMembershipsEnabled(true);
    Team eng = new Team();
    eng.setTeamId("eng");
    Team ops = new Team();
    ops.setTeamId("ops");
    when(teamCatalogService.find("eng")).thenReturn(Optional.of(eng));
    when(teamCatalogService.find("ops")).thenReturn(Optional.of(ops));
    when(tokenClient.exchangeAndValidate(anyString(), anyString(), any()))
        .thenReturn(
            new OidcIdTokenClaims(
                "https://idp.example", "sub-1", null, List.of("eng", "ops", "eng")));
    IdentityMapping mapping = new IdentityMapping();
    mapping.setUsername("alice");
    when(mappingService.findByIssuerAndSubject(anyString(), anyString()))
        .thenReturn(Optional.of(mapping));
    when(userService.isEnabledUser("alice")).thenReturn(true);
    WebSession session = new WebSession();
    session.setSessionId("sid");
    session.setUsername("alice");
    when(sessionService.create("alice")).thenReturn(session);

    assertThat(service.completeLogin("code", "st").isSuccess()).isTrue();
    verify(teamMembershipService, times(1)).add("alice", "eng");
    verify(teamMembershipService, times(1)).add("alice", "ops");
  }

  @Test
  @DisplayName("JIT team memberships开_再登录仍幂等调用add")
  void callback_jitTeamMembershipsOn_idempotentRelogin() {
    properties.setJitTeamMembershipsEnabled(true);
    Team eng = new Team();
    eng.setTeamId("eng");
    when(teamCatalogService.find("eng")).thenReturn(Optional.of(eng));
    when(tokenClient.exchangeAndValidate(anyString(), anyString(), any()))
        .thenReturn(new OidcIdTokenClaims("https://idp.example", "sub-1", null, List.of("eng")));
    IdentityMapping mapping = new IdentityMapping();
    mapping.setUsername("alice");
    when(mappingService.findByIssuerAndSubject(anyString(), anyString()))
        .thenReturn(Optional.of(mapping));
    when(userService.isEnabledUser("alice")).thenReturn(true);
    WebSession session = new WebSession();
    session.setSessionId("sid");
    session.setUsername("alice");
    when(sessionService.create("alice")).thenReturn(session);

    pendingStore.put("st1", "verifier");
    assertThat(service.completeLogin("code", "st1").isSuccess()).isTrue();
    pendingStore.put("st2", "verifier");
    assertThat(service.completeLogin("code", "st2").isSuccess()).isTrue();
    verify(teamMembershipService, times(2)).add("alice", "eng");
  }

  @Test
  @DisplayName("JIT team memberships开_无catalog行则跳过add")
  void callback_jitTeamMembershipsOn_skipsMissingCatalog() {
    pendingStore.put("st", "verifier");
    properties.setJitTeamMembershipsEnabled(true);
    when(teamCatalogService.find("eng")).thenReturn(Optional.empty());
    when(tokenClient.exchangeAndValidate(anyString(), anyString(), any()))
        .thenReturn(new OidcIdTokenClaims("https://idp.example", "sub-1", null, List.of("eng")));
    IdentityMapping mapping = new IdentityMapping();
    mapping.setUsername("alice");
    when(mappingService.findByIssuerAndSubject(anyString(), anyString()))
        .thenReturn(Optional.of(mapping));
    when(userService.isEnabledUser("alice")).thenReturn(true);
    WebSession session = new WebSession();
    session.setSessionId("sid");
    session.setUsername("alice");
    when(sessionService.create("alice")).thenReturn(session);

    assertThat(service.completeLogin("code", "st").isSuccess()).isTrue();
    verify(teamMembershipService, never()).add(anyString(), anyString());
  }
}
