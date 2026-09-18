package io.oryxos.web.security;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session 作用域组织声明（041 / #560）：由登录时 {@code teamIds} × {@code teams.org_id} 派生；请求期填入 {@code
 * Principal.orgIds}。进程内缓存、不落库——重启丢失。flag {@code oryxos.web.rbac.org-ids-from-team-org-enabled}
 * 关时不应写入。
 */
public final class SessionOrgIdsCache {

  private final Map<String, Set<String>> bySessionId = new ConcurrentHashMap<>();

  /** 登录成功后绑定；orgIds 空则清除该 session 的声明。 */
  public void put(String sessionId, List<String> orgIds) {
    if (sessionId == null || sessionId.isBlank()) {
      return;
    }
    Set<String> cleaned = clean(orgIds);
    if (cleaned.isEmpty()) {
      bySessionId.remove(sessionId);
      return;
    }
    bySessionId.put(sessionId, cleaned);
  }

  /** 登录成功后绑定（集合入参）。 */
  public void put(String sessionId, Set<String> orgIds) {
    if (orgIds == null || orgIds.isEmpty()) {
      put(sessionId, List.of());
      return;
    }
    put(sessionId, List.copyOf(orgIds));
  }

  /** 请求期读取；未知 session → 空集。 */
  public Set<String> get(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return Set.of();
    }
    Set<String> found = bySessionId.get(sessionId);
    return found == null ? Set.of() : found;
  }

  /** 登出 / 失效时移除。 */
  public void remove(String sessionId) {
    if (sessionId != null && !sessionId.isBlank()) {
      bySessionId.remove(sessionId);
    }
  }

  private static Set<String> clean(List<String> orgIds) {
    if (orgIds == null || orgIds.isEmpty()) {
      return Set.of();
    }
    Set<String> cleaned = new LinkedHashSet<>();
    for (String orgId : orgIds) {
      if (orgId == null || orgId.isBlank()) {
        continue;
      }
      cleaned.add(orgId.strip());
    }
    return cleaned.isEmpty() ? Set.of() : Collections.unmodifiableSet(cleaned);
  }
}
