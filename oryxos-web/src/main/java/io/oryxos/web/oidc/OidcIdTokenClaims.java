package io.oryxos.web.oidc;

import java.util.List;

/**
 * 经验证的 id_token 声明子集（040 / #502）。callback 用 iss/sub 做身份映射；{@code preferredUsername}/{@code email} 供
 * JIT 推导本地用户名；{@code groups} 只供组→角色同步，不做 AuthorizationService 裁决。
 *
 * @param issuer iss
 * @param subject sub
 * @param email 可选 email claim
 * @param preferredUsername 可选 preferred_username claim
 * @param groups 可选组声明；缺省空列表
 */
public record OidcIdTokenClaims(
    String issuer, String subject, String email, String preferredUsername, List<String> groups) {

  public OidcIdTokenClaims {
    groups = groups == null ? List.of() : List.copyOf(groups);
  }

  /** 既有三参调用点：无 preferred_username / 组声明。 */
  public OidcIdTokenClaims(String issuer, String subject, String email) {
    this(issuer, subject, email, null, List.of());
  }

  /** 既有四参调用点：无 preferred_username。 */
  public OidcIdTokenClaims(String issuer, String subject, String email, List<String> groups) {
    this(issuer, subject, email, null, groups);
  }
}
