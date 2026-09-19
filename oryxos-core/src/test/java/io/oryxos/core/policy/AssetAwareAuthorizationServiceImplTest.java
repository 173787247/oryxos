package io.oryxos.core.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.auth.Role;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelConfigLoader;
import io.oryxos.core.policy.AuthorizationService.Decision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 041 资产门禁装饰器：flag 关必须原样透传；开时 OFFLINE / PRIVATE 叠加，缺元数据不加拒绝。 */
class AssetAwareAuthorizationServiceImplTest {

  private static final String AGENT = "ops-agent";

  private static final String OWNER = "alice";

  private static final String OTHER = "bob";

  private static final String KEY_NAME = "ci-key";

  @TempDir Path root;

  @Test
  void flagOffPassesThroughEvenWhenOffline() throws Exception {
    writeAgent(offlinePrivate(OWNER));
    AuthorizationService stub = allowAllButCounting();
    AssetAwareAuthorizationServiceImpl service =
        new AssetAwareAuthorizationServiceImpl(stub, new AssetGovernanceStore(root), false);

    Decision decision = service.decide(user(OTHER), Action.RUN_AGENT, ResourceRef.agent(AGENT));

    assertThat(decision.allowed()).isTrue();
    assertThat(((CountingAuth) stub).calls).isEqualTo(1);
  }

  @Test
  void offlineDeniesWhenFlagOn() throws Exception {
    writeAgent(offlinePrivate(OWNER));
    AssetAwareAuthorizationServiceImpl service = enabled(allowAllButCounting());

    Decision decision = service.decide(user(OWNER), Action.RUN_AGENT, ResourceRef.agent(AGENT));

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).isEqualTo(AssetAwareAuthorizationServiceImpl.REASON_OFFLINE);
  }

  @Test
  void privateOwnerAllowedOtherUserDeniedAdminAllowed() throws Exception {
    writeAgent(activePrivate(OWNER));
    AssetAwareAuthorizationServiceImpl service = enabled(allowAllButCounting());

    assertThat(
            service.decide(user(OWNER), Action.MANAGE_AGENTS, ResourceRef.agent(AGENT)).allowed())
        .isTrue();
    Decision other = service.decide(user(OTHER), Action.MANAGE_AGENTS, ResourceRef.agent(AGENT));
    assertThat(other.allowed()).isFalse();
    assertThat(other.reason()).contains("私有");
    assertThat(
            service.decide(admin(OTHER), Action.MANAGE_AGENTS, ResourceRef.agent(AGENT)).allowed())
        .isTrue();
  }

  @Test
  void missingMetaAllows() {
    AssetAwareAuthorizationServiceImpl service = enabled(allowAllButCounting());

    Decision decision = service.decide(user(OTHER), Action.RUN_AGENT, ResourceRef.agent(AGENT));

    assertThat(decision.allowed()).isTrue();
  }

  @Test
  void delegateDenyShortCircuitsWithoutAssetGate() throws Exception {
    writeAgent(activePrivate(OWNER));
    AuthorizationService deny = (principal, action, resource) -> Decision.denied("角色不足");
    AssetAwareAuthorizationServiceImpl service =
        new AssetAwareAuthorizationServiceImpl(deny, new AssetGovernanceStore(root), true);

    Decision decision = service.decide(user(OTHER), Action.MANAGE_AGENTS, ResourceRef.agent(AGENT));

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).isEqualTo("角色不足");
  }

  @Test
  void apiKeyOnlyBlockedByOffline() throws Exception {
    writeAgent(activePrivate(OWNER));
    AssetAwareAuthorizationServiceImpl service = enabled(allowAllButCounting());
    Principal key = Principal.apiKey(KEY_NAME, KEY_NAME, Set.of(Role.EDITOR));

    assertThat(service.decide(key, Action.RUN_AGENT, ResourceRef.agent(AGENT)).allowed()).isTrue();

    writeAgent(offlinePrivate(OWNER));
    Decision offline = service.decide(key, Action.RUN_AGENT, ResourceRef.agent(AGENT));
    assertThat(offline.allowed()).isFalse();
    assertThat(offline.reason()).isEqualTo(AssetAwareAuthorizationServiceImpl.REASON_OFFLINE);
  }

  private AssetAwareAuthorizationServiceImpl enabled(AuthorizationService delegate) {
    return new AssetAwareAuthorizationServiceImpl(delegate, new AssetGovernanceStore(root), true);
  }

  private void writeAgent(AssetGovernance governance) throws Exception {
    Path dir = root.resolve("agents").resolve(AGENT);
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve(AssetGovernanceStore.FILE_NAME), AssetGovernanceStore.render(governance));
  }

  private static AssetGovernance activePrivate(String owner) {
    return new AssetGovernance(
        owner, "1", AssetGovernance.Visibility.PRIVATE, null, AssetGovernance.Health.ACTIVE);
  }

  private static AssetGovernance offlinePrivate(String owner) {
    return new AssetGovernance(
        owner, "1", AssetGovernance.Visibility.PRIVATE, null, AssetGovernance.Health.OFFLINE);
  }

  private static Principal user(String id) {
    return Principal.user(id, id, Set.of(Role.EDITOR));
  }

  private static Principal admin(String id) {
    return Principal.user(id, id, Set.of(Role.ADMIN));
  }

  private static AuthorizationService allowAllButCounting() {
    return new CountingAuth();
  }

  /** 计数桩：证明装饰器只打同一接口一次委托。 */
  private static final class CountingAuth implements AuthorizationService {
    private int calls;

    @Override
    public Decision decide(Principal principal, Action action, ResourceRef resource) {
      calls++;
      return Decision.ALLOWED;
    }
  }

  @Test
  void channelOfflineDeniedWhenFlagOn() {
    writeChannel(
        new AssetGovernance(
            OWNER, "1", AssetGovernance.Visibility.PRIVATE, null, AssetGovernance.Health.OFFLINE));
    AssetAwareAuthorizationServiceImpl service = enabled(allowAllButCounting());

    Decision decision =
        service.decide(user(OWNER), Action.MANAGE_CHANNELS, ResourceRef.channel(CHANNEL));

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).isEqualTo(AssetAwareAuthorizationServiceImpl.REASON_OFFLINE);
  }

  @Test
  void channelFlagOffDoesNotExtraDeny() {
    writeChannel(
        new AssetGovernance(
            OWNER, "1", AssetGovernance.Visibility.PRIVATE, null, AssetGovernance.Health.OFFLINE));
    AssetAwareAuthorizationServiceImpl service =
        new AssetAwareAuthorizationServiceImpl(
            allowAllButCounting(), new AssetGovernanceStore(root), false);

    Decision decision =
        service.decide(user(OTHER), Action.MANAGE_CHANNELS, ResourceRef.channel(CHANNEL));

    assertThat(decision.allowed()).isTrue();
  }

  @Test
  void workspaceTeamAclOffDoesNotExtraDeny() throws Exception {
    writeAgent(workspaceWithTeam("ops"));
    AssetAwareAuthorizationServiceImpl service =
        new AssetAwareAuthorizationServiceImpl(
            allowAllButCounting(), new AssetGovernanceStore(root), true, false);

    assertThat(
            service.decide(user(OTHER), Action.READ_WORKSPACE, ResourceRef.agent(AGENT)).allowed())
        .isTrue();
  }

  @Test
  void workspaceTeamAclSameTeamAllowedOtherDeniedAdminAllowed() throws Exception {
    writeAgent(workspaceWithTeam("ops"));
    AssetAwareAuthorizationServiceImpl service =
        new AssetAwareAuthorizationServiceImpl(
            allowAllButCounting(), new AssetGovernanceStore(root), true, true);

    assertThat(
            service
                .decide(
                    Principal.user(OWNER, OWNER, Set.of(Role.EDITOR), Set.of("ops")),
                    Action.READ_WORKSPACE,
                    ResourceRef.agent(AGENT))
                .allowed())
        .isTrue();
    Decision other =
        service.decide(
            Principal.user(OTHER, OTHER, Set.of(Role.EDITOR), Set.of("other")),
            Action.READ_WORKSPACE,
            ResourceRef.agent(AGENT));
    assertThat(other.allowed()).isFalse();
    assertThat(other.reason()).isEqualTo(AssetAwareAuthorizationServiceImpl.REASON_WORKSPACE_TEAM);
    assertThat(
            service.decide(admin(OTHER), Action.READ_WORKSPACE, ResourceRef.agent(AGENT)).allowed())
        .isTrue();
  }

  @Test
  void workspaceWithoutTeamOwnerStillAllowsWhenAclOn() throws Exception {
    writeAgent(
        new AssetGovernance(
            OWNER, "1", AssetGovernance.Visibility.WORKSPACE, null, AssetGovernance.Health.ACTIVE));
    AssetAwareAuthorizationServiceImpl service =
        new AssetAwareAuthorizationServiceImpl(
            allowAllButCounting(), new AssetGovernanceStore(root), true, true);

    assertThat(
            service.decide(user(OTHER), Action.READ_WORKSPACE, ResourceRef.agent(AGENT)).allowed())
        .isTrue();
  }

  @Test
  void workspaceOrgAclOffDoesNotExtraDeny() throws Exception {
    writeAgent(workspaceWithOrg("acme"));
    AssetAwareAuthorizationServiceImpl service =
        new AssetAwareAuthorizationServiceImpl(
            allowAllButCounting(),
            new AssetGovernanceStore(root),
            true,
            false,
            false,
            teamId -> Optional.of("acme"));

    assertThat(
            service
                .decide(
                    Principal.user(OTHER, OTHER, Set.of(Role.EDITOR), Set.of("other")),
                    Action.READ_WORKSPACE,
                    ResourceRef.agent(AGENT))
                .allowed())
        .isTrue();
  }

  @Test
  void workspaceOrgAclMatchingOrgAllowedWrongDeniedAdminAllowed() throws Exception {
    writeAgent(workspaceWithOrg("acme"));
    TeamOrgLookup lookup =
        teamId -> {
          if ("ops".equals(teamId)) {
            return Optional.of("acme");
          }
          if ("other".equals(teamId)) {
            return Optional.of("other-org");
          }
          return Optional.empty();
        };
    AssetAwareAuthorizationServiceImpl service =
        new AssetAwareAuthorizationServiceImpl(
            allowAllButCounting(), new AssetGovernanceStore(root), true, false, true, lookup);

    assertThat(
            service
                .decide(
                    Principal.user(OWNER, OWNER, Set.of(Role.EDITOR), Set.of("ops")),
                    Action.READ_WORKSPACE,
                    ResourceRef.agent(AGENT))
                .allowed())
        .isTrue();
    Decision other =
        service.decide(
            Principal.user(OTHER, OTHER, Set.of(Role.EDITOR), Set.of("other")),
            Action.READ_WORKSPACE,
            ResourceRef.agent(AGENT));
    assertThat(other.allowed()).isFalse();
    assertThat(other.reason()).isEqualTo(AssetAwareAuthorizationServiceImpl.REASON_WORKSPACE_ORG);
    assertThat(
            service.decide(admin(OTHER), Action.READ_WORKSPACE, ResourceRef.agent(AGENT)).allowed())
        .isTrue();
  }

  @Test
  void workspaceOrgAclPrefersPrincipalOrgIdsWhenPresent() throws Exception {
    writeAgent(workspaceWithOrg("acme"));
    // lookup 故意说 ops→other-org；若走 lookup 会拒。orgIds 含 acme 时应放行。
    TeamOrgLookup hostileLookup = teamId -> Optional.of("other-org");
    AssetAwareAuthorizationServiceImpl service =
        new AssetAwareAuthorizationServiceImpl(
            allowAllButCounting(),
            new AssetGovernanceStore(root),
            true,
            false,
            true,
            hostileLookup);

    assertThat(
            service
                .decide(
                    Principal.user(
                        OWNER, OWNER, Set.of(Role.EDITOR), Set.of("ops"), Set.of("acme")),
                    Action.READ_WORKSPACE,
                    ResourceRef.agent(AGENT))
                .allowed())
        .isTrue();
    Decision wrongOrg =
        service.decide(
            Principal.user(OTHER, OTHER, Set.of(Role.EDITOR), Set.of("ops"), Set.of("other-org")),
            Action.READ_WORKSPACE,
            ResourceRef.agent(AGENT));
    assertThat(wrongOrg.allowed()).isFalse();
    assertThat(wrongOrg.reason())
        .isEqualTo(AssetAwareAuthorizationServiceImpl.REASON_WORKSPACE_ORG);
  }

  @Test
  void workspaceOrgAclFallsBackToTeamLookupWhenOrgIdsEmpty() throws Exception {
    writeAgent(workspaceWithOrg("acme"));
    TeamOrgLookup lookup = teamId -> "ops".equals(teamId) ? Optional.of("acme") : Optional.empty();
    AssetAwareAuthorizationServiceImpl service =
        new AssetAwareAuthorizationServiceImpl(
            allowAllButCounting(), new AssetGovernanceStore(root), true, false, true, lookup);

    assertThat(
            service
                .decide(
                    Principal.user(OWNER, OWNER, Set.of(Role.EDITOR), Set.of("ops"), Set.of()),
                    Action.READ_WORKSPACE,
                    ResourceRef.agent(AGENT))
                .allowed())
        .isTrue();
  }

  @Test
  void workspaceWithoutOrgOwnerStillAllowsWhenOrgAclOn() throws Exception {
    writeAgent(
        new AssetGovernance(
            OWNER, "1", AssetGovernance.Visibility.WORKSPACE, null, AssetGovernance.Health.ACTIVE));
    AssetAwareAuthorizationServiceImpl service =
        new AssetAwareAuthorizationServiceImpl(
            allowAllButCounting(),
            new AssetGovernanceStore(root),
            true,
            false,
            true,
            teamId -> Optional.of("acme"));

    assertThat(
            service.decide(user(OTHER), Action.READ_WORKSPACE, ResourceRef.agent(AGENT)).allowed())
        .isTrue();
  }

  @Test
  void workspaceOrgAclAncestorOffKeepsExactMatchOnly() throws Exception {
    writeAgent(workspaceWithOrg("eng"));
    // parent member has orgIds={acme}; without ancestor flag must deny (exact only).
    OrgParentLookup parents = orgId -> "eng".equals(orgId) ? Optional.of("acme") : Optional.empty();
    AssetAwareAuthorizationServiceImpl service =
        new AssetAwareAuthorizationServiceImpl(
            allowAllButCounting(),
            new AssetGovernanceStore(root),
            true,
            false,
            true,
            teamId -> Optional.empty(),
            false,
            parents);

    Decision parentMember =
        service.decide(
            Principal.user(OTHER, OTHER, Set.of(Role.EDITOR), Set.of(), Set.of("acme")),
            Action.READ_WORKSPACE,
            ResourceRef.agent(AGENT));
    assertThat(parentMember.allowed()).isFalse();
    assertThat(parentMember.reason())
        .isEqualTo(AssetAwareAuthorizationServiceImpl.REASON_WORKSPACE_ORG);
    assertThat(
            service
                .decide(
                    Principal.user(OWNER, OWNER, Set.of(Role.EDITOR), Set.of(), Set.of("eng")),
                    Action.READ_WORKSPACE,
                    ResourceRef.agent(AGENT))
                .allowed())
        .isTrue();
  }

  @Test
  void workspaceOrgAclAncestorOnParentMemberAccessesChildOrgOwner() throws Exception {
    writeAgent(workspaceWithOrg("eng"));
    OrgParentLookup parents = orgId -> "eng".equals(orgId) ? Optional.of("acme") : Optional.empty();
    AssetAwareAuthorizationServiceImpl service =
        new AssetAwareAuthorizationServiceImpl(
            allowAllButCounting(),
            new AssetGovernanceStore(root),
            true,
            false,
            true,
            teamId -> Optional.empty(),
            true,
            parents);

    assertThat(
            service
                .decide(
                    Principal.user(OTHER, OTHER, Set.of(Role.EDITOR), Set.of(), Set.of("acme")),
                    Action.READ_WORKSPACE,
                    ResourceRef.agent(AGENT))
                .allowed())
        .isTrue();
    Decision unrelated =
        service.decide(
            Principal.user(OTHER, OTHER, Set.of(Role.EDITOR), Set.of(), Set.of("other-org")),
            Action.READ_WORKSPACE,
            ResourceRef.agent(AGENT));
    assertThat(unrelated.allowed()).isFalse();
    assertThat(unrelated.reason())
        .isEqualTo(AssetAwareAuthorizationServiceImpl.REASON_WORKSPACE_ORG);
  }

  @Test
  void workspaceOrgAclAncestorDepthBoundStopsLoops() throws Exception {
    writeAgent(workspaceWithOrg("a"));
    // a -> b -> a cycle; principal has unrelated org — must terminate without match.
    OrgParentLookup cyclic =
        orgId -> {
          if ("a".equals(orgId)) {
            return Optional.of("b");
          }
          if ("b".equals(orgId)) {
            return Optional.of("a");
          }
          return Optional.empty();
        };
    AssetAwareAuthorizationServiceImpl service =
        new AssetAwareAuthorizationServiceImpl(
            allowAllButCounting(),
            new AssetGovernanceStore(root),
            true,
            false,
            true,
            teamId -> Optional.empty(),
            true,
            cyclic);

    Decision denied =
        service.decide(
            Principal.user(OTHER, OTHER, Set.of(Role.EDITOR), Set.of(), Set.of("zzz")),
            Action.READ_WORKSPACE,
            ResourceRef.agent(AGENT));
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.reason()).isEqualTo(AssetAwareAuthorizationServiceImpl.REASON_WORKSPACE_ORG);
  }

  @Test
  void workspaceOrgAclAncestorCustomDepthStopsBeforeGrandparent() throws Exception {
    writeAgent(workspaceWithOrg("leaf"));
    // leaf -> mid -> root; principal has root; depth=1 reaches mid only → deny
    OrgParentLookup parents =
        orgId -> {
          if ("leaf".equals(orgId)) {
            return Optional.of("mid");
          }
          if ("mid".equals(orgId)) {
            return Optional.of("root");
          }
          return Optional.empty();
        };
    AssetAwareAuthorizationServiceImpl shallow =
        new AssetAwareAuthorizationServiceImpl(
            allowAllButCounting(),
            new AssetGovernanceStore(root),
            true,
            false,
            true,
            teamId -> Optional.empty(),
            true,
            parents,
            1);
    Decision denied =
        shallow.decide(
            Principal.user(OTHER, OTHER, Set.of(Role.EDITOR), Set.of(), Set.of("root")),
            Action.READ_WORKSPACE,
            ResourceRef.agent(AGENT));
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.reason()).isEqualTo(AssetAwareAuthorizationServiceImpl.REASON_WORKSPACE_ORG);

    AssetAwareAuthorizationServiceImpl deepEnough =
        new AssetAwareAuthorizationServiceImpl(
            allowAllButCounting(),
            new AssetGovernanceStore(root),
            true,
            false,
            true,
            teamId -> Optional.empty(),
            true,
            parents,
            2);
    assertThat(
            deepEnough
                .decide(
                    Principal.user(OTHER, OTHER, Set.of(Role.EDITOR), Set.of(), Set.of("root")),
                    Action.READ_WORKSPACE,
                    ResourceRef.agent(AGENT))
                .allowed())
        .isTrue();
  }

  @Test
  void workspaceOrgAclAncestorAdminAndApiKeyUnchanged() throws Exception {
    writeAgent(workspaceWithOrg("eng"));
    OrgParentLookup parents = orgId -> "eng".equals(orgId) ? Optional.of("acme") : Optional.empty();
    AssetAwareAuthorizationServiceImpl service =
        new AssetAwareAuthorizationServiceImpl(
            allowAllButCounting(),
            new AssetGovernanceStore(root),
            true,
            false,
            true,
            teamId -> Optional.empty(),
            true,
            parents);

    assertThat(
            service.decide(admin(OTHER), Action.READ_WORKSPACE, ResourceRef.agent(AGENT)).allowed())
        .isTrue();
    assertThat(
            service
                .decide(
                    Principal.apiKey(KEY_NAME, KEY_NAME, Set.of(Role.EDITOR)),
                    Action.READ_WORKSPACE,
                    ResourceRef.agent(AGENT))
                .allowed())
        .isTrue();
  }

  private static AssetGovernance workspaceWithTeam(String team) {
    return new AssetGovernance(
        OWNER,
        "1",
        AssetGovernance.Visibility.WORKSPACE,
        null,
        AssetGovernance.Health.ACTIVE,
        team);
  }

  private static AssetGovernance workspaceWithOrg(String org) {
    return new AssetGovernance(
        OWNER,
        "1",
        AssetGovernance.Visibility.WORKSPACE,
        null,
        AssetGovernance.Health.ACTIVE,
        null,
        org);
  }

  private static final String CHANNEL = "ops-feishu";

  private void writeChannel(AssetGovernance governance) {
    new ChannelConfigLoader(root.resolve(AssetGovernanceStore.CHANNELS_FILE))
        .save(
            List.of(
                new ChannelConfig(CHANNEL, "feishu", "app", "secret", AGENT, true)
                    .withGovernance(governance)));
  }
}
