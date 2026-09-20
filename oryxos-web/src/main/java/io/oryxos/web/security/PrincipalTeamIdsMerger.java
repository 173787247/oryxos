package io.oryxos.web.security;

import io.oryxos.storage.TeamMembershipService;
import io.oryxos.web.config.WebRbacProperties;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 合并 session 团队声明与持久化成员（#535）。
 *
 * <p>仅当 {@code oryxos.web.rbac.durable-team-memberships-enabled=true} 时读库；默认关则行为与仅 session 缓存一致。
 */
public final class PrincipalTeamIdsMerger {

  private final WebRbacProperties rbac;
  private final TeamMembershipService memberships;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "rbac/memberships 均为 Spring 注入共享单例，构造注入存同一引用正是意图。")
  public PrincipalTeamIdsMerger(WebRbacProperties rbac, TeamMembershipService memberships) {
    this.rbac = rbac;
    this.memberships = memberships;
  }

  /**
   * @param username 本地用户名；可空（则只返回 session 侧）
   * @param sessionTeamIds session 缓存（OIDC groups / user-team-ids）；可空
   */
  public Set<String> merge(String username, Set<String> sessionTeamIds) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    if (sessionTeamIds != null) {
      for (String id : sessionTeamIds) {
        if (id != null && !id.isBlank()) {
          out.add(id.strip());
        }
      }
    }
    if (rbac != null
        && rbac.isDurableTeamMembershipsEnabled()
        && memberships != null
        && username != null
        && !username.isBlank()) {
      out.addAll(memberships.listTeamIds(username));
    }
    return Set.copyOf(out);
  }
}
