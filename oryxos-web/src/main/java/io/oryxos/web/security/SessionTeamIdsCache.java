package io.oryxos.web.security;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session 作用域团队声明（041 / #504）：OIDC 登录写 id_token groups；密码登录可写 {@code oryxos.web.auth.user-team-ids}
 * 配置。请求期填入 {@code Principal.teamIds}。进程内缓存、不落库——重启丢失。真正的 teams 表 / JIT 仍 defer。
 */
public final class SessionTeamIdsCache {

  private final Map<String, Set<String>> bySessionId = new ConcurrentHashMap<>();

  /** 登录成功后绑定；groups 空则清除该 session 的声明。 */
  public void put(String sessionId, List<String> groups) {
    if (sessionId == null || sessionId.isBlank()) {
      return;
    }
    Set<String> cleaned = clean(groups);
    if (cleaned.isEmpty()) {
      bySessionId.remove(sessionId);
      return;
    }
    bySessionId.put(sessionId, cleaned);
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

  private static Set<String> clean(List<String> groups) {
    if (groups == null || groups.isEmpty()) {
      return Set.of();
    }
    Set<String> cleaned = new LinkedHashSet<>();
    for (String group : groups) {
      if (group == null || group.isBlank()) {
        continue;
      }
      cleaned.add(group.strip());
    }
    return cleaned.isEmpty() ? Set.of() : Collections.unmodifiableSet(cleaned);
  }
}
