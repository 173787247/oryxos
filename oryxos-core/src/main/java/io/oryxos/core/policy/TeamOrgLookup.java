package io.oryxos.core.policy;

import java.util.Optional;

/**
 * 团队 → 组织 id 查找（041 / #558）：供 WORKSPACE+{@code orgOwner} 门禁把 {@code Principal.teamIds} 映射到 {@code
 * teams.org_id}。实现通常委托存储层目录；core 不依赖 storage。
 */
@FunctionalInterface
public interface TeamOrgLookup {

  /** 无目录 / 未知 team / 未设 org 时返回 empty。 */
  Optional<String> findOrgId(String teamId);
}
