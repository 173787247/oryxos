package io.oryxos.web.oidc;

import io.oryxos.core.auth.Role;
import io.oryxos.core.policy.TeamOrgLookup;
import io.oryxos.storage.AuthEventRecorder;
import io.oryxos.storage.AuthEventType;
import io.oryxos.storage.IdentityMapping;
import io.oryxos.storage.IdentityMappingService;
import io.oryxos.storage.TeamCatalogService;
import io.oryxos.storage.TeamMembershipService;
import io.oryxos.storage.WebSession;
import io.oryxos.storage.WebSessionService;
import io.oryxos.storage.WebUserService;
import io.oryxos.web.config.WebOidcProperties;
import io.oryxos.web.config.WebRbacProperties;
import io.oryxos.web.security.SessionOrgIdsCache;
import io.oryxos.web.security.SessionOrgIdsFromTeams;
import io.oryxos.web.security.SessionTeamIdsCache;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OIDC 授权码 + PKCE 登录编排（040 / #461）。
 *
 * <p><b>刻意不依赖</b>{@code AuthorizationService}——callback 只做认证、映射与建 {@link WebSession}；授权留给既有
 * session→Principal→Filter 路径（#462）。组→角色只写 {@code web_users.roles}，不在这里做角色比对或
 * AuthorizationService.decide。
 */
public class OidcAuthService {

  private static final Logger LOG = LoggerFactory.getLogger(OidcAuthService.class);

  private static final SecureRandom RANDOM = new SecureRandom();

  /** 与 {@code WebUserService} 用户名长度上限对齐（JIT 推导名不得超长）。 */
  private static final int MAX_JIT_USERNAME_LENGTH = 64;

  private static final char EMAIL_LOCAL_SEPARATOR = '@';

  private final WebOidcProperties properties;
  private final OidcTokenClient tokenClient;
  private final OidcPendingStore pendingStore;
  private final IdentityMappingService mappingService;
  private final WebUserService userService;
  private final WebSessionService sessionService;
  private final AuthEventRecorder authEventRecorder;
  private final OidcGroupRoleSync groupRoleSync;
  private final SessionTeamIdsCache teamIdsCache;
  private final TeamCatalogService teamCatalogService;
  private final TeamMembershipService teamMembershipService;
  private final SessionOrgIdsCache orgIdsCache;
  private final WebRbacProperties rbacProperties;
  private final TeamOrgLookup teamOrgLookup;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "全部为 Spring 注入共享单例，存同一引用正是意图。")
  public OidcAuthService(
      WebOidcProperties properties,
      OidcTokenClient tokenClient,
      OidcPendingStore pendingStore,
      IdentityMappingService mappingService,
      WebUserService userService,
      WebSessionService sessionService,
      AuthEventRecorder authEventRecorder) {
    this(
        properties,
        tokenClient,
        pendingStore,
        mappingService,
        userService,
        sessionService,
        authEventRecorder,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "全部为 Spring 注入共享单例，存同一引用正是意图。")
  public OidcAuthService(
      WebOidcProperties properties,
      OidcTokenClient tokenClient,
      OidcPendingStore pendingStore,
      IdentityMappingService mappingService,
      WebUserService userService,
      WebSessionService sessionService,
      AuthEventRecorder authEventRecorder,
      SessionTeamIdsCache teamIdsCache) {
    this(
        properties,
        tokenClient,
        pendingStore,
        mappingService,
        userService,
        sessionService,
        authEventRecorder,
        teamIdsCache,
        null,
        null,
        null,
        null,
        null);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "全部为 Spring 注入共享单例，存同一引用正是意图。")
  public OidcAuthService(
      WebOidcProperties properties,
      OidcTokenClient tokenClient,
      OidcPendingStore pendingStore,
      IdentityMappingService mappingService,
      WebUserService userService,
      WebSessionService sessionService,
      AuthEventRecorder authEventRecorder,
      SessionTeamIdsCache teamIdsCache,
      TeamCatalogService teamCatalogService) {
    this(
        properties,
        tokenClient,
        pendingStore,
        mappingService,
        userService,
        sessionService,
        authEventRecorder,
        teamIdsCache,
        teamCatalogService,
        null,
        null,
        null,
        null);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "全部为 Spring 注入共享单例，存同一引用正是意图。")
  public OidcAuthService(
      WebOidcProperties properties,
      OidcTokenClient tokenClient,
      OidcPendingStore pendingStore,
      IdentityMappingService mappingService,
      WebUserService userService,
      WebSessionService sessionService,
      AuthEventRecorder authEventRecorder,
      SessionTeamIdsCache teamIdsCache,
      TeamCatalogService teamCatalogService,
      SessionOrgIdsCache orgIdsCache,
      WebRbacProperties rbacProperties,
      TeamOrgLookup teamOrgLookup) {
    this(
        properties,
        tokenClient,
        pendingStore,
        mappingService,
        userService,
        sessionService,
        authEventRecorder,
        teamIdsCache,
        teamCatalogService,
        orgIdsCache,
        rbacProperties,
        teamOrgLookup,
        null);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "全部为 Spring 注入共享单例，存同一引用正是意图。")
  public OidcAuthService(
      WebOidcProperties properties,
      OidcTokenClient tokenClient,
      OidcPendingStore pendingStore,
      IdentityMappingService mappingService,
      WebUserService userService,
      WebSessionService sessionService,
      AuthEventRecorder authEventRecorder,
      SessionTeamIdsCache teamIdsCache,
      TeamCatalogService teamCatalogService,
      SessionOrgIdsCache orgIdsCache,
      WebRbacProperties rbacProperties,
      TeamOrgLookup teamOrgLookup,
      TeamMembershipService teamMembershipService) {
    this.properties = properties;
    this.tokenClient = tokenClient;
    this.pendingStore = pendingStore;
    this.mappingService = mappingService;
    this.userService = userService;
    this.sessionService = sessionService;
    this.authEventRecorder = authEventRecorder;
    this.groupRoleSync = new OidcGroupRoleSync(userService);
    this.teamIdsCache = teamIdsCache == null ? new SessionTeamIdsCache() : teamIdsCache;
    this.teamCatalogService = teamCatalogService;
    this.teamMembershipService = teamMembershipService;
    this.orgIdsCache = orgIdsCache == null ? new SessionOrgIdsCache() : orgIdsCache;
    this.rbacProperties = rbacProperties;
    this.teamOrgLookup = teamOrgLookup;
  }

  public boolean isEnabled() {
    return properties.isEnabled();
  }

  /** 生成 state+PKCE，返回 IdP authorize URL。 */
  public String beginLogin() {
    String state = randomUrlSafe(32);
    String verifier = randomUrlSafe(32);
    pendingStore.put(state, verifier);
    String challenge = s256Challenge(verifier);
    String authorize = tokenClient.resolveAuthorizationEndpoint(properties);
    String scopes =
        properties.getScopes() == null || properties.getScopes().isBlank()
            ? "openid profile email"
            : properties.getScopes().strip();
    return authorize
        + (authorize.contains("?") ? "&" : "?")
        + "response_type=code"
        + "&client_id="
        + enc(properties.getClientId())
        + "&redirect_uri="
        + enc(properties.getRedirectUri())
        + "&scope="
        + enc(scopes)
        + "&state="
        + enc(state)
        + "&code_challenge="
        + enc(challenge)
        + "&code_challenge_method=S256";
  }

  /**
   * 完成 callback：校验 state → 换码验 token → 查映射 → 验本地用户启用 → 建 session。
   *
   * <p>LOGIN_SUCCESS 审计走 {@link AuthEventRecorder#recordOrThrow}：写失败则拒绝建 session（fail-closed）。
   */
  public OidcLoginResult completeLogin(String code, String state) {
    if (code == null || code.isBlank() || state == null || state.isBlank()) {
      fail("missing_code_or_state", null);
      return OidcLoginResult.failure("missing code or state");
    }
    Optional<OidcPendingLogin> pending = pendingStore.take(state);
    if (pending.isEmpty()) {
      fail("invalid_or_expired_state", null);
      return OidcLoginResult.failure("invalid or expired state");
    }
    OidcIdTokenClaims claims;
    try {
      claims = tokenClient.exchangeAndValidate(code, pending.get().codeVerifier(), properties);
    } catch (RuntimeException ex) {
      LOG.warn("OIDC token 路径失败：{}", ex.toString().replace('\r', '_').replace('\n', '_'));
      fail("token_exchange_or_validation_failed", null);
      return OidcLoginResult.failure("OIDC token exchange or validation failed");
    }
    Optional<IdentityMapping> mapping =
        mappingService.findByIssuerAndSubject(claims.issuer(), claims.subject());
    if (mapping.isEmpty()) {
      if (!properties.isJitProvisionEnabled()) {
        fail("unmapped_subject", claims.subject());
        return OidcLoginResult.failure("identity not mapped");
      }
      mapping = tryJitProvision(claims);
      if (mapping.isEmpty()) {
        return OidcLoginResult.failure("identity not mapped");
      }
    }
    String username = mapping.get().getUsername();
    if (!userService.isEnabledUser(username)) {
      fail("user_missing_or_disabled", username);
      return OidcLoginResult.failure("user missing or disabled");
    }
    if (!syncGroupRoles(username, claims)) {
      return OidcLoginResult.failure("group role sync failed");
    }
    ensureTeamCatalogRows(claims.groups());
    ensureTeamMemberships(username, claims.groups());
    try {
      authEventRecorder.recordOrThrow(
          AuthEventType.LOGIN_SUCCESS, username, "oidc issuer=" + claims.issuer());
    } catch (RuntimeException ex) {
      LOG.error(
          "LOGIN_SUCCESS 审计失败，拒绝建 session：{}", ex.toString().replace('\r', '_').replace('\n', '_'));
      return OidcLoginResult.failure("auth audit failed");
    }
    WebSession session = sessionService.create(username);
    teamIdsCache.put(session.getSessionId(), claims.groups());
    cacheOrgIdsFromTeams(session.getSessionId(), claims.groups());
    return OidcLoginResult.success(session, username);
  }

  /** #560：flag 开时由 groups/teamIds 派生 orgIds 写入 session 缓存；关则清掉。 */
  private void cacheOrgIdsFromTeams(String sessionId, java.util.Collection<String> teamIds) {
    if (rbacProperties == null || !rbacProperties.isOrgIdsFromTeamOrgEnabled()) {
      orgIdsCache.remove(sessionId);
      return;
    }
    LinkedHashSet<String> teams = new LinkedHashSet<>();
    if (teamIds != null) {
      for (String t : teamIds) {
        if (t != null && !t.isBlank()) {
          teams.add(t.strip());
        }
      }
    }
    orgIdsCache.put(sessionId, SessionOrgIdsFromTeams.resolve(teams, teamOrgLookup));
  }

  /**
   * JIT（#502）：flag 关 → 空；开则按 preferred_username / email 建用户并 upsert mapping。缺可用用户名 claim →
   * fail-closed（不把裸 sub 当 username）。
   */
  private Optional<IdentityMapping> tryJitProvision(OidcIdTokenClaims claims) {
    Optional<String> username = resolveJitUsername(claims);
    if (username.isEmpty()) {
      fail("jit_username_unavailable", claims.subject());
      return Optional.empty();
    }
    try {
      userService.ensureOidcProvisioned(username.get());
      IdentityMapping saved =
          mappingService.upsert(claims.issuer(), claims.subject(), username.get(), claims.email());
      return Optional.of(saved);
    } catch (RuntimeException ex) {
      LOG.warn("OIDC JIT 失败：{}", sanitize(ex.toString()));
      fail("jit_provision_failed", username.get());
      return Optional.empty();
    }
  }

  /** preferred_username → email local-part；均非法则空。不用裸 sub。 */
  static Optional<String> resolveJitUsername(OidcIdTokenClaims claims) {
    Optional<String> fromPreferred = sanitizeJitUsername(claims.preferredUsername());
    if (fromPreferred.isPresent()) {
      return fromPreferred;
    }
    String email = claims.email();
    if (email == null || email.isBlank() || email.indexOf(EMAIL_LOCAL_SEPARATOR) < 0) {
      return Optional.empty();
    }
    return sanitizeJitUsername(email.substring(0, email.indexOf(EMAIL_LOCAL_SEPARATOR)));
  }

  private static Optional<String> sanitizeJitUsername(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    String trimmed = raw.strip();
    if (trimmed.length() > MAX_JIT_USERNAME_LENGTH
        || trimmed.chars().anyMatch(Character::isWhitespace)) {
      return Optional.empty();
    }
    return Optional.of(trimmed);
  }

  /** Flag 开时对 IdP groups 幂等写 {@code teams} 目录行（#552）。失败只打日志，不阻断登录（catalog 为可选元数据）。 */
  private void ensureTeamCatalogRows(List<String> groups) {
    if (!properties.isJitTeamCatalogEnabled() || teamCatalogService == null) {
      return;
    }
    if (groups == null || groups.isEmpty()) {
      return;
    }
    for (String group : new LinkedHashSet<>(groups)) {
      if (group == null || group.isBlank()) {
        continue;
      }
      try {
        teamCatalogService.ensure(group);
      } catch (RuntimeException ex) {
        LOG.warn(
            "OIDC JIT team catalog ensure 失败 group={}：{}",
            sanitize(group),
            sanitize(ex.toString()));
      }
    }
  }

  /**
   * Flag 开时对 IdP groups 幂等写 {@code team_memberships}（#562）。无 catalog 行则跳过并打日志；add 失败只打日志，不阻断登录。本
   * cut 不撤销未出现在 groups 中的成员关系。
   */
  private void ensureTeamMemberships(String username, List<String> groups) {
    if (!properties.isJitTeamMembershipsEnabled() || teamMembershipService == null) {
      return;
    }
    if (groups == null || groups.isEmpty()) {
      return;
    }
    for (String group : new LinkedHashSet<>(groups)) {
      if (group == null || group.isBlank()) {
        continue;
      }
      if (teamCatalogService != null && teamCatalogService.find(group).isEmpty()) {
        LOG.warn(
            "OIDC JIT team membership skip (no catalog row) group={} user={}",
            sanitize(group),
            sanitize(username));
        continue;
      }
      try {
        teamMembershipService.add(username, group);
      } catch (RuntimeException ex) {
        LOG.warn(
            "OIDC JIT team membership add 失败 group={} user={}：{}",
            sanitize(group),
            sanitize(username),
            sanitize(ex.toString()));
      }
    }
  }

  /**
   * 映射表为空时不写角色。命中或 revoke 写入则审计；写库或审计失败则拒绝建 session。不调用 AuthorizationService。
   *
   * @return false 表示登录应失败
   */
  private boolean syncGroupRoles(String username, OidcIdTokenClaims claims) {
    Optional<Set<Role>> synced;
    try {
      synced = groupRoleSync.apply(username, claims.groups(), properties);
    } catch (RuntimeException ex) {
      LOG.warn("OIDC 组角色写入失败：{}", sanitize(ex.toString()));
      fail("group_role_sync_failed", username);
      return false;
    }
    if (synced.isEmpty()) {
      return true;
    }
    try {
      authEventRecorder.recordOrThrow(
          AuthEventType.GROUP_ROLE_SYNC, username, "roles=" + synced.get());
    } catch (RuntimeException ex) {
      LOG.error("GROUP_ROLE_SYNC 审计失败，拒绝建 session：{}", sanitize(ex.toString()));
      fail("group_role_sync_audit_failed", username);
      return false;
    }
    return true;
  }

  private static String sanitize(String value) {
    return value.replace('\r', '_').replace('\n', '_');
  }

  private void fail(String detail, String principalId) {
    authEventRecorder.recordBestEffort(AuthEventType.LOGIN_FAILURE, principalId, detail);
  }

  private static String s256Challenge(String verifier) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }

  private static String randomUrlSafe(int bytes) {
    byte[] buf = new byte[bytes];
    RANDOM.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
  }

  private static String enc(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  /** callback 结果：成功带 session；失败带可读原因（对外可折叠为统一 401）。 */
  public static final class OidcLoginResult {
    private final boolean success;
    private final WebSession session;
    private final String username;
    private final String error;

    private OidcLoginResult(boolean success, WebSession session, String username, String error) {
      this.success = success;
      this.session = session;
      this.username = username;
      this.error = error;
    }

    public static OidcLoginResult success(WebSession session, String username) {
      return new OidcLoginResult(true, session, username, null);
    }

    public static OidcLoginResult failure(String error) {
      return new OidcLoginResult(false, null, null, error);
    }

    public boolean isSuccess() {
      return success;
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "Intentional handoff of newly created WebSession to Controller for cookie write.")
    public WebSession getSession() {
      return session;
    }

    public String getUsername() {
      return username;
    }

    public String getError() {
      return error;
    }

    @Override
    public String toString() {
      return "OidcLoginResult{success="
          + success
          + ", username='"
          + username
          + "', error='"
          + (error == null ? "" : error.toLowerCase(Locale.ROOT))
          + "'}";
    }
  }
}
