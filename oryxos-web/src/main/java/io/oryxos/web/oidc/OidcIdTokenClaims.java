package io.oryxos.web.oidc;

import java.util.List;

/**
 * 经验证的 id_token 声明子集（040 / #502 / #590）。callback 用 iss/sub 做身份映射；{@code preferredUsername}/{@code
 * email} 供 JIT 推导本地用户名；{@code groups} 只供组→角色 / 团队路径；{@code orgIds} 供专用 org-ids claim → session
 * Principal.orgIds（不做 AuthorizationService 裁决、不写 organizations catalog）。
 *
 * @param issuer iss
 * @param subject sub
 * @param email 可选 email claim
 * @param preferredUsername 可选 preferred_username claim
 * @param groups 可选组声明；缺省空列表
 * @param orgIds 可选组织 id 声明；缺省空列表（仅当配置了 org-ids-claim 时由 token 客户端填充）
 */
public record OidcIdTokenClaims(
    String issuer,
    String subject,
    String email,
    String preferredUsername,
    List<String> groups,
    List<String> orgIds) {

  public OidcIdTokenClaims {
    groups = groups == null ? List.of() : List.copyOf(groups);
    orgIds = orgIds == null ? List.of() : List.copyOf(orgIds);
  }

  /** 既有三参调用点：无 preferred_username / 组 / org 声明。 */
  public OidcIdTokenClaims(String issuer, String subject, String email) {
    this(issuer, subject, email, null, List.of(), List.of());
  }

  /** 既有四参调用点：无 preferred_username / org 声明。 */
  public OidcIdTokenClaims(String issuer, String subject, String email, List<String> groups) {
    this(issuer, subject, email, null, groups, List.of());
  }

  /** 五参：无 org 声明（兼容旧构造）。 */
  public OidcIdTokenClaims(
      String issuer, String subject, String email, String preferredUsername, List<String> groups) {
    this(issuer, subject, email, preferredUsername, groups, List.of());
  }
}
