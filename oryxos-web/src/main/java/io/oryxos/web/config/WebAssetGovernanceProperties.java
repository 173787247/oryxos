package io.oryxos.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 资产治理开关（041 / #463）。
 *
 * <p>{@code oryxos.web.asset-governance.enabled} 默认 {@code false}——关闭时 {@link
 * io.oryxos.core.policy.AssetAwareAuthorizationServiceImpl} 不叠加任何拒绝，行为与无侧车时代一致。开启后仍要求 {@code
 * oryxos.web.rbac.enabled=true}：没有角色主体，资产归属门禁没有对象可判。
 *
 * <p>{@code workspace-team-acl-enabled} 默认 {@code false}——关时 WORKSPACE 与 PUBLIC 同档不另拒；开时对带 {@code
 * teamOwner} 的 WORKSPACE 资产要求同队或 ADMIN。
 *
 * <p>{@code workspace-org-acl-enabled} 默认 {@code false}——开时对带 {@code orgOwner} 的 WORKSPACE 资产要求
 * Principal 持有某 teamId 且该队 {@code teams.org_id} 等于 orgOwner（或 ADMIN）。
 *
 * <p>{@code workspace-org-acl-ancestor-enabled} 默认 {@code false}——仅在 org ACL 已开时生效；开时沿 {@code
 * parent_org_id} 上行匹配祖先（#568）。
 */
@ConfigurationProperties(prefix = "oryxos.web.asset-governance")
public class WebAssetGovernanceProperties {

  /** 是否叠加 OFFLINE / PRIVATE 资产门禁。默认关。 */
  private boolean enabled = false;

  /** 是否叠加 WORKSPACE+teamOwner 门禁。默认关。仅在 {@link #enabled} 为 true 且 RBAC 已开时由装配层传入装饰器。 */
  private boolean workspaceTeamAclEnabled = false;

  /** 是否叠加 WORKSPACE+orgOwner 门禁。默认关。仅在 {@link #enabled} 为 true 且 RBAC 已开时由装配层传入装饰器。 */
  private boolean workspaceOrgAclEnabled = false;

  /** 是否叠加 WORKSPACE+orgOwner 祖先匹配。默认关。须同时 {@link #workspaceOrgAclEnabled}；装配层传入装饰器。 */
  private boolean workspaceOrgAclAncestorEnabled = false;

  /**
   * 是否在治理 PUT 时追加全文快照到 {@code asset_governance_revisions}（#537）。默认关：仅 V11 change_summary
   * 事件，行为与引入版本历史前一致。
   */
  private boolean versionHistoryEnabled = false;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isWorkspaceTeamAclEnabled() {
    return workspaceTeamAclEnabled;
  }

  public void setWorkspaceTeamAclEnabled(boolean workspaceTeamAclEnabled) {
    this.workspaceTeamAclEnabled = workspaceTeamAclEnabled;
  }

  public boolean isWorkspaceOrgAclEnabled() {
    return workspaceOrgAclEnabled;
  }

  public void setWorkspaceOrgAclEnabled(boolean workspaceOrgAclEnabled) {
    this.workspaceOrgAclEnabled = workspaceOrgAclEnabled;
  }

  public boolean isWorkspaceOrgAclAncestorEnabled() {
    return workspaceOrgAclAncestorEnabled;
  }

  public void setWorkspaceOrgAclAncestorEnabled(boolean workspaceOrgAclAncestorEnabled) {
    this.workspaceOrgAclAncestorEnabled = workspaceOrgAclAncestorEnabled;
  }

  public boolean isVersionHistoryEnabled() {
    return versionHistoryEnabled;
  }

  public void setVersionHistoryEnabled(boolean versionHistoryEnabled) {
    this.versionHistoryEnabled = versionHistoryEnabled;
  }
}
