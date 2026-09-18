package io.oryxos.web.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

/**
 * 管理台 Basic Auth 配置（012-web-auth）。
 *
 * <p>{@code oryxos.web.auth.enabled} 默认 {@code false}——假设内网现状不变（宪法核心阶段边界）； 置 {@code true} 后 {@code
 * /admin/**} 启用认证（浏览器走 session/cookie + 登录页，curl 走 Basic Auth）， {@code /api/v1/**} 不受影响。
 *
 * <p>{@code user-team-ids}（041 / #504）：可选 username→团队 id 列表。密码登录成功后写入 {@code SessionTeamIdsCache}，供
 * WORKSPACE teamOwner 门禁；默认空=密码登录 session 无 teamIds（与升级前一致）。非 durable teams 表。
 */
@ConfigurationProperties(prefix = "oryxos.web.auth")
public class WebAuthProperties {

  /** 是否启用管理台认证。默认关：保持"假设内网"现状。 */
  private boolean enabled = false;

  /** Basic Auth realm 文案（curl 401 时 WWW-Authenticate 用）。默认 "OryxOS"。 */
  private String realm = "OryxOS";

  /** session 过期时间（web_sessions.expires_at = created_at + ttl）。默认 12 小时。 */
  @DurationUnit(ChronoUnit.HOURS)
  private Duration sessionTtl = Duration.ofHours(12);

  /** 本地用户 → 团队 id（与 OIDC groups / {@code Principal.teamIds} 对齐）。缺省空 map。 */
  private Map<String, List<String>> userTeamIds = new LinkedHashMap<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getRealm() {
    return realm;
  }

  public void setRealm(String realm) {
    this.realm = realm;
  }

  public Duration getSessionTtl() {
    return sessionTtl;
  }

  public void setSessionTtl(Duration sessionTtl) {
    this.sessionTtl = sessionTtl;
  }

  /** 返回防御性深拷贝（SpotBugs EI_EXPOSE_REP）。 */
  public Map<String, List<String>> getUserTeamIds() {
    if (userTeamIds == null || userTeamIds.isEmpty()) {
      return Map.of();
    }
    Map<String, List<String>> copy = new LinkedHashMap<>();
    userTeamIds.forEach((k, v) -> copy.put(k, v == null ? List.of() : List.copyOf(v)));
    return Map.copyOf(copy);
  }

  public void setUserTeamIds(Map<String, List<String>> userTeamIds) {
    if (userTeamIds == null) {
      this.userTeamIds = new LinkedHashMap<>();
      return;
    }
    Map<String, List<String>> copy = new LinkedHashMap<>();
    userTeamIds.forEach((k, v) -> copy.put(k, v == null ? new ArrayList<>() : new ArrayList<>(v)));
    this.userTeamIds = copy;
  }

  /** 密码登录用：查配置中的团队声明；无条目 → 空列表。 */
  public List<String> teamIdsFor(String username) {
    if (username == null || username.isBlank() || userTeamIds == null || userTeamIds.isEmpty()) {
      return List.of();
    }
    List<String> found = userTeamIds.get(username);
    if (found == null || found.isEmpty()) {
      return List.of();
    }
    List<String> cleaned = new ArrayList<>();
    for (String id : found) {
      if (id == null || id.isBlank()) {
        continue;
      }
      cleaned.add(id.strip());
    }
    return List.copyOf(cleaned);
  }
}
