package io.oryxos.core.policy;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.auth.Role;
import java.util.LinkedHashSet;
import java.util.Optional;

/**
 * 资产治理装饰器（041 / #463）：先走既有 {@link AuthorizationService#decide}，再在开关打开且角色已允许时 叠加 OFFLINE / PRIVATE
 * （及可选 WORKSPACE team / org）门禁。
 *
 * <p>唯一权限路径仍是本接口——本类是装饰器，不是第二条授权通道。flag 关或委托已拒绝时原样返回，保证默认关零变化。 缺侧车（空治理）不加额外拒绝。
 *
 * <p>API_KEY：本刀只挡 OFFLINE，不做 owner / team / org 匹配（Key 名称不是账号归属模型）。
 */
public final class AssetAwareAuthorizationServiceImpl implements AuthorizationService {

  /** OFFLINE 拒绝理由（固定文案，进审计）。 */
  public static final String REASON_OFFLINE = "资产已安全下线";

  private static final String REASON_PRIVATE = "私有资产仅属主或管理员可访问";

  /** WORKSPACE+teamOwner 拒绝理由（固定文案，进审计）。 */
  public static final String REASON_WORKSPACE_TEAM = "工作区资产仅同队成员或管理员可访问";

  /** WORKSPACE+orgOwner 拒绝理由（固定文案，进审计）。 */
  public static final String REASON_WORKSPACE_ORG = "工作区资产仅同组织成员或管理员可访问";

  private final AuthorizationService delegate;

  private final AssetGovernanceStore store;

  private final boolean enabled;

  private final boolean workspaceTeamAclEnabled;

  private final boolean workspaceOrgAclEnabled;

  private final TeamOrgLookup teamOrgLookup;

  private final boolean workspaceOrgAclAncestorEnabled;

  private final OrgParentLookup orgParentLookup;

  /** parent_org_id 上行最大跳数（含环时靠深度截断；不含 orgOwner 自身）。 */
  static final int MAX_ORG_ANCESTOR_DEPTH = 16;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "delegate/store 为注入共享单例，存同一引用正是意图。")
  public AssetAwareAuthorizationServiceImpl(
      AuthorizationService delegate, AssetGovernanceStore store, boolean enabled) {
    this(delegate, store, enabled, false, false, null, false, null);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "delegate/store 为注入共享单例，存同一引用正是意图。")
  public AssetAwareAuthorizationServiceImpl(
      AuthorizationService delegate,
      AssetGovernanceStore store,
      boolean enabled,
      boolean workspaceTeamAclEnabled) {
    this(delegate, store, enabled, workspaceTeamAclEnabled, false, null, false, null);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "delegate/store/lookup 为注入共享单例，存同一引用正是意图。")
  public AssetAwareAuthorizationServiceImpl(
      AuthorizationService delegate,
      AssetGovernanceStore store,
      boolean enabled,
      boolean workspaceTeamAclEnabled,
      boolean workspaceOrgAclEnabled,
      TeamOrgLookup teamOrgLookup) {
    this(
        delegate,
        store,
        enabled,
        workspaceTeamAclEnabled,
        workspaceOrgAclEnabled,
        teamOrgLookup,
        false,
        null);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "delegate/store/lookup 为注入共享单例，存同一引用正是意图。")
  public AssetAwareAuthorizationServiceImpl(
      AuthorizationService delegate,
      AssetGovernanceStore store,
      boolean enabled,
      boolean workspaceTeamAclEnabled,
      boolean workspaceOrgAclEnabled,
      TeamOrgLookup teamOrgLookup,
      boolean workspaceOrgAclAncestorEnabled,
      OrgParentLookup orgParentLookup) {
    this.delegate = delegate == null ? AuthorizationService.ALLOW_ALL : delegate;
    if (store == null) {
      throw new IllegalArgumentException("store 不能为空");
    }
    this.store = store;
    this.enabled = enabled;
    this.workspaceTeamAclEnabled = workspaceTeamAclEnabled;
    this.workspaceOrgAclEnabled = workspaceOrgAclEnabled;
    this.teamOrgLookup = teamOrgLookup;
    this.workspaceOrgAclAncestorEnabled = workspaceOrgAclAncestorEnabled;
    this.orgParentLookup = orgParentLookup;
  }

  @Override
  public Decision decide(Principal principal, Action action, ResourceRef resource) {
    Decision delegated = delegate.decide(principal, action, resource);
    if (!enabled || !delegated.allowed()) {
      return delegated;
    }
    if (resource == null || resource.id() == null || resource.id().isBlank()) {
      return delegated;
    }
    AssetGovernance governance = store.load(resource.type(), resource.id());
    if (!governance.isPresent()) {
      return delegated;
    }
    if (governance.health() == AssetGovernance.Health.OFFLINE) {
      return Decision.denied(REASON_OFFLINE);
    }
    Decision privateDecision = privateGate(principal, governance);
    if (!privateDecision.allowed()) {
      return privateDecision;
    }
    Decision teamDecision = workspaceTeamGate(principal, governance);
    if (!teamDecision.allowed()) {
      return teamDecision;
    }
    return workspaceOrgGate(principal, governance);
  }

  /** PRIVATE：仅 USER 且 owner 存在且不匹配、又非 ADMIN 时拒绝。API_KEY / 匿名 / 无 owner 本刀不在这里拒绝。 */
  private static Decision privateGate(Principal principal, AssetGovernance governance) {
    if (governance.visibility() != AssetGovernance.Visibility.PRIVATE) {
      return Decision.ALLOWED;
    }
    Principal subject = principal == null ? Principal.anonymous() : principal;
    if (subject.kind() != Principal.Kind.USER) {
      return Decision.ALLOWED;
    }
    if (subject.hasRole(Role.ADMIN)) {
      return Decision.ALLOWED;
    }
    String owner = governance.owner();
    if (owner == null || owner.isBlank()) {
      return Decision.ALLOWED;
    }
    if (owner.equals(subject.id())) {
      return Decision.ALLOWED;
    }
    return Decision.denied(REASON_PRIVATE);
  }

  /**
   * WORKSPACE + teamOwner：仅 {@code workspaceTeamAclEnabled} 时生效。无 teamOwner 不另拒（存量兼容）。API_KEY /
   * 匿名跳过（与 PRIVATE 同口径）；USER 须同队或 ADMIN。
   */
  private Decision workspaceTeamGate(Principal principal, AssetGovernance governance) {
    if (!workspaceTeamAclEnabled) {
      return Decision.ALLOWED;
    }
    if (governance.visibility() != AssetGovernance.Visibility.WORKSPACE) {
      return Decision.ALLOWED;
    }
    String teamOwner = governance.teamOwner();
    if (teamOwner == null || teamOwner.isBlank()) {
      return Decision.ALLOWED;
    }
    Principal subject = principal == null ? Principal.anonymous() : principal;
    if (subject.kind() != Principal.Kind.USER) {
      return Decision.ALLOWED;
    }
    if (subject.hasRole(Role.ADMIN)) {
      return Decision.ALLOWED;
    }
    if (subject.hasTeam(teamOwner)) {
      return Decision.ALLOWED;
    }
    return Decision.denied(REASON_WORKSPACE_TEAM);
  }

  /**
   * WORKSPACE + orgOwner：仅 {@code workspaceOrgAclEnabled} 时生效。无 orgOwner 不另拒。API_KEY / 匿名跳过；USER
   * 优先用 {@code Principal.orgIds}（#560）；空则回退 teamIds × {@link TeamOrgLookup}（#558 兼容）。{@code
   * workspaceOrgAclAncestorEnabled} 开时与 {@link OrgParentLookup} 沿 parent_org_id 上行匹配祖先（#568）。
   */
  private Decision workspaceOrgGate(Principal principal, AssetGovernance governance) {
    if (!workspaceOrgAclEnabled) {
      return Decision.ALLOWED;
    }
    if (governance.visibility() != AssetGovernance.Visibility.WORKSPACE) {
      return Decision.ALLOWED;
    }
    String orgOwner = governance.orgOwner();
    if (orgOwner == null || orgOwner.isBlank()) {
      return Decision.ALLOWED;
    }
    Principal subject = principal == null ? Principal.anonymous() : principal;
    if (subject.kind() != Principal.Kind.USER) {
      return Decision.ALLOWED;
    }
    if (subject.hasRole(Role.ADMIN)) {
      return Decision.ALLOWED;
    }
    if (belongsToOrg(subject, orgOwner.strip())) {
      return Decision.ALLOWED;
    }
    return Decision.denied(REASON_WORKSPACE_ORG);
  }

  private boolean belongsToOrg(Principal subject, String orgOwner) {
    LinkedHashSet<String> principalOrgs = new LinkedHashSet<>();
    if (!subject.orgIds().isEmpty()) {
      principalOrgs.addAll(subject.orgIds());
    } else if (teamOrgLookup != null) {
      for (String teamId : subject.teamIds()) {
        Optional<String> orgId = teamOrgLookup.findOrgId(teamId);
        if (orgId.isPresent() && !orgId.get().isBlank()) {
          principalOrgs.add(orgId.get().strip());
        }
      }
    }
    if (principalOrgs.isEmpty()) {
      return false;
    }
    if (!workspaceOrgAclAncestorEnabled) {
      return principalOrgs.contains(orgOwner);
    }
    String current = orgOwner;
    for (int depth = 0; depth <= MAX_ORG_ANCESTOR_DEPTH; depth++) {
      if (principalOrgs.contains(current)) {
        return true;
      }
      if (orgParentLookup == null || depth == MAX_ORG_ANCESTOR_DEPTH) {
        return false;
      }
      Optional<String> parent = orgParentLookup.findParentOrgId(current);
      if (parent.isEmpty() || parent.get().isBlank()) {
        return false;
      }
      String next = parent.get().strip();
      if (next.equals(current)) {
        return false;
      }
      current = next;
    }
    return false;
  }
}
