package io.oryxos.web.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.policy.TeamOrgLookup;
import io.oryxos.storage.AuthEventRecorder;
import io.oryxos.storage.IdentityMapping;
import io.oryxos.storage.IdentityMappingService;
import io.oryxos.storage.WebSession;
import io.oryxos.storage.WebSessionService;
import io.oryxos.storage.WebUserService;
import io.oryxos.web.config.WebOidcProperties;
import io.oryxos.web.config.WebRbacProperties;
import io.oryxos.web.oidc.OidcAuthService.OidcLoginResult;
import io.oryxos.web.security.SessionOrgIdsCache;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #590：OIDC org-ids-claim → Principal.orgIds（与 groups/teams 分离；可与 team-derived 并集）。 */
class OidcOrgIdsClaimTest {

  private WebOidcProperties properties;
  private WebRbacProperties rbacProperties;
  private OidcTokenClient tokenClient;
  private OidcPendingStore pendingStore;
  private IdentityMappingService mappingService;
  private WebUserService userService;
  private WebSessionService sessionService;
  private AuthEventRecorder authEventRecorder;
  private SessionOrgIdsCache orgIdsCache;
  private TeamOrgLookup teamOrgLookup;
  private OidcAuthService service;

  @BeforeEach
  void setUp() {
    properties = new WebOidcProperties();
    properties.setEnabled(true);
    properties.setIssuer("https://idp.example");
    properties.setClientId("oryxos");
    properties.setRedirectUri("https://app.example/api/v1/auth/oidc/callback");
    rbacProperties = new WebRbacProperties();
    tokenClient = mock(OidcTokenClient.class);
    pendingStore = new OidcPendingStore(java.time.Duration.ofMinutes(10));
    mappingService = mock(IdentityMappingService.class);
    userService = mock(WebUserService.class);
    sessionService = mock(WebSessionService.class);
    authEventRecorder = mock(AuthEventRecorder.class);
    orgIdsCache = new SessionOrgIdsCache();
    teamOrgLookup = teamId -> "eng".equals(teamId) ? Optional.of("team-org") : Optional.empty();
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
            null,
            orgIdsCache,
            rbacProperties,
            teamOrgLookup,
            null);
  }

  @Test
  @DisplayName("org-ids-claim空_不写入claim orgIds")
  void emptyClaimName_doesNotCacheClaimOrgs() {
    properties.setOrgIdsClaim("");
    stubMappedLogin(
        new OidcIdTokenClaims(
            "https://idp.example", "sub-1", null, null, List.of(), List.of("should-ignore")));

    OidcLoginResult result = service.completeLogin("code", "st");
    assertThat(result.isSuccess()).isTrue();
    assertThat(orgIdsCache.get("sid")).isEmpty();
  }

  @Test
  @DisplayName("org-ids-claim配置且claim有值_写入session orgIds")
  void claimPresent_cachesOrgIds() {
    properties.setOrgIdsClaim("orgs");
    stubMappedLogin(
        new OidcIdTokenClaims(
            "https://idp.example", "sub-1", null, null, List.of("eng"), List.of("acme", "beta")));

    assertThat(service.completeLogin("code", "st").isSuccess()).isTrue();
    assertThat(orgIdsCache.get("sid")).containsExactly("acme", "beta");
  }

  @Test
  @DisplayName("缺claim_软失败登录成功且orgIds空")
  void missingClaim_loginSucceedsWithEmptyOrgIds() {
    properties.setOrgIdsClaim("orgs");
    stubMappedLogin(
        new OidcIdTokenClaims(
            "https://idp.example", "sub-1", null, null, List.of("eng"), List.of()));

    assertThat(service.completeLogin("code", "st").isSuccess()).isTrue();
    assertThat(orgIdsCache.get("sid")).isEmpty();
  }

  @Test
  @DisplayName("claim与team-derived同时开_取并集")
  void unionWithTeamDerivedOrgIds() {
    properties.setOrgIdsClaim("orgs");
    rbacProperties.setOrgIdsFromTeamOrgEnabled(true);
    stubMappedLogin(
        new OidcIdTokenClaims(
            "https://idp.example", "sub-1", null, null, List.of("eng"), List.of("claim-org")));

    assertThat(service.completeLogin("code", "st").isSuccess()).isTrue();
    assertThat(orgIdsCache.get("sid")).containsExactlyInAnyOrder("claim-org", "team-org");
  }

  @Test
  @DisplayName("仅team-derived开_无claim名时仍派生")
  void teamDerivedOnly_whenClaimNameBlank() {
    properties.setOrgIdsClaim("");
    rbacProperties.setOrgIdsFromTeamOrgEnabled(true);
    stubMappedLogin(
        new OidcIdTokenClaims(
            "https://idp.example", "sub-1", null, null, List.of("eng"), List.of("ignored")));

    assertThat(service.completeLogin("code", "st").isSuccess()).isTrue();
    assertThat(orgIdsCache.get("sid")).containsExactly("team-org");
  }

  private void stubMappedLogin(OidcIdTokenClaims claims) {
    pendingStore.put("st", "verifier");
    when(tokenClient.exchangeAndValidate(eq("code"), eq("verifier"), any())).thenReturn(claims);
    IdentityMapping mapping = new IdentityMapping();
    mapping.setUsername("alice");
    when(mappingService.findByIssuerAndSubject(anyString(), anyString()))
        .thenReturn(Optional.of(mapping));
    when(userService.isEnabledUser("alice")).thenReturn(true);
    WebSession session = new WebSession();
    session.setSessionId("sid");
    session.setUsername("alice");
    when(sessionService.create("alice")).thenReturn(session);
  }
}
