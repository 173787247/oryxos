package io.oryxos.core.policy;

import java.util.Optional;

/**
 * 团队 → 父团队 id 查找（041 / #588）：供 WORKSPACE+{@code teamOwner} 祖先匹配沿 {@code teams.parent_team_id}
 * 上行。实现通常委托存储层目录；core 不依赖 storage。
 */
@FunctionalInterface
public interface TeamParentLookup {

  /**
   * parent_team_id 上行最大跳数默认值（#588 decide 祖先匹配；#581 setParent 环检测复用）。与 {@link
   * OrgParentLookup#MAX_ORG_ANCESTOR_DEPTH} 同值；运行时可被 {@code
   * oryxos.web.asset-governance.max-org-ancestor-depth} 覆盖（默认本常量）。
   */
  int MAX_TEAM_ANCESTOR_DEPTH = OrgParentLookup.MAX_ORG_ANCESTOR_DEPTH;

  /** 无目录 / 未知 team / 未设父时返回 empty。 */
  Optional<String> findParentTeamId(String teamId);
}
