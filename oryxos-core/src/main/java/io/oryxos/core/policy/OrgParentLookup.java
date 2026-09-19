package io.oryxos.core.policy;

import java.util.Optional;

/**
 * 组织 → 父组织 id 查找（041 / #568）：供 WORKSPACE+{@code orgOwner} 祖先匹配沿 {@code organizations.parent_org_id}
 * 上行。实现通常委托存储层目录；core 不依赖 storage。
 */
@FunctionalInterface
public interface OrgParentLookup {

  /** 无目录 / 未知 org / 未设父时返回 empty。 */
  Optional<String> findParentOrgId(String orgId);
}
