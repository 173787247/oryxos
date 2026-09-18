package io.oryxos.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 资源授权（RBAC）配置（039-identity-authorization）。
 *
 * <p>{@code oryxos.web.rbac.enabled} 默认 {@code false}——与 012 {@code oryxos.web.auth.enabled}、018
 * {@code oryxos.web.apikey.enabled} 同一条纪律：新能力默认关，未启用时行为与引入授权层之前逐字节一致 （018 SC-001
 * 的「回归零破坏」是硬成功标准，不是尽力而为）。置 {@code true} 后，两扇既有 Filter 在认证成功 时把请求主体交给唯一决策点 {@link
 * io.oryxos.core.policy.AuthorizationService} 裁决，拒绝即 403 并留审计。
 *
 * <p><b>一个必须写进配置注释的坑</b>：即使 {@code rbac.enabled=true}，如果 {@code web.auth.enabled} 与 {@code
 * web.apikey.enabled} 仍为 {@code false}，则没有任何门会产出主体——所有请求都是匿名主体，RBAC 会被
 * 短路为「放行但无主体」。这是刻意设计：默认关的认证意味着「假设内网」，此时若让 RBAC 直接拒绝全部请求， 单机零配置部署会被自己的授权层锁死。要真正生效，三个开关的关系是「认证开 →
 * 授权才有对象」。
 */
@ConfigurationProperties(prefix = "oryxos.web.rbac")
public class WebRbacProperties {

  /** 是否启用资源授权裁决。默认关：保持既有行为（无授权层）不变。 */
  private boolean enabled = false;

  /** 未认证请求（匿名主体）在启用授权时是否直接拒绝。默认 {@code true}——授权启用后默认拒绝是安全默认值。 */
  private boolean denyAnonymous = true;

  /**
   * 是否把 V12 {@code team_memberships} 并入 {@code Principal.teamIds}（#535）。默认关：仅 session 缓存（OIDC
   * groups / user-team-ids）生效，与引入持久化成员前行为一致。
   */
  private boolean durableTeamMembershipsEnabled = false;

  /**
   * 是否由 session {@code teamIds} 经 {@code teams.org_id} 派生并缓存 {@code Principal.orgIds}（#560）。默认关：主体无
   * orgIds；orgOwner 门禁仍可走即时 team×lookup。
   */
  private boolean orgIdsFromTeamOrgEnabled = false;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isDenyAnonymous() {
    return denyAnonymous;
  }

  public void setDenyAnonymous(boolean denyAnonymous) {
    this.denyAnonymous = denyAnonymous;
  }

  public boolean isDurableTeamMembershipsEnabled() {
    return durableTeamMembershipsEnabled;
  }

  public void setDurableTeamMembershipsEnabled(boolean durableTeamMembershipsEnabled) {
    this.durableTeamMembershipsEnabled = durableTeamMembershipsEnabled;
  }

  public boolean isOrgIdsFromTeamOrgEnabled() {
    return orgIdsFromTeamOrgEnabled;
  }

  public void setOrgIdsFromTeamOrgEnabled(boolean orgIdsFromTeamOrgEnabled) {
    this.orgIdsFromTeamOrgEnabled = orgIdsFromTeamOrgEnabled;
  }
}
