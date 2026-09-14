package io.oryxos.web.config;

import io.oryxos.core.auth.Role;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 授权默认角色配置（039-identity-authorization）。
 *
 * <p>存在的理由：039 第一刀不含角色落库（成员管理与角色注册留后续），但启用授权后主体必须能拿到角色，否则
 * 所有已认证请求都会被拒。因此提供两个默认值入口，让部署方在角色存储落地前也能用起来。
 *
 * <p>安全默认值：
 *
 * <ul>
 *   <li>{@code roles.default-user-roles} 默认 {@code ADMIN}——单管理员的单机部署升级后不应被自己的授权层锁死； 多人企业管理台应显式调低为
 *       {@code VIEWER}/{@code EDITOR}。
 *   <li>{@code roles.default-api-key-roles} 默认<b>空</b>——机器凭证不给默认权限。这也是与「API Key 上限不含成员与策略
 *       管理」一致的保守口径。
 * </ul>
 *
 * <p>前缀刻意用 {@code oryxos.web.rbac.roles} 而不是 {@code oryxos.web.rbac}：后者已被 {@link WebRbacProperties}
 * 占用，两个 {@code @ConfigurationProperties} 绑定同一前缀会在启动时冲突。
 */
@ConfigurationProperties(prefix = "oryxos.web.rbac.roles")
public class RoleMappingProperties {

  /** 管理台账号未自带角色时的默认角色。落库后应收紧为空；过渡期仍默认 ADMIN 防单机自锁。 */
  private Set<String> defaultUserRoles = new LinkedHashSet<>(Set.of(Role.ADMIN.name()));

  /** API Key 未自带角色时的默认角色。默认空 = 拒绝（机器凭证不给默认权限）。 */
  private Set<String> defaultApiKeyRoles = new LinkedHashSet<>();

  /** 返回防御性拷贝，避免调用方改动内部集合（SpotBugs EI_EXPOSE_REP）。 */
  public Set<String> getDefaultUserRoles() {
    return Set.copyOf(defaultUserRoles);
  }

  public void setDefaultUserRoles(Set<String> defaultUserRoles) {
    this.defaultUserRoles =
        defaultUserRoles == null ? new LinkedHashSet<>() : new LinkedHashSet<>(defaultUserRoles);
  }

  /** 返回防御性拷贝，避免调用方改动内部集合（SpotBugs EI_EXPOSE_REP）。 */
  public Set<String> getDefaultApiKeyRoles() {
    return Set.copyOf(defaultApiKeyRoles);
  }

  public void setDefaultApiKeyRoles(Set<String> defaultApiKeyRoles) {
    this.defaultApiKeyRoles =
        defaultApiKeyRoles == null
            ? new LinkedHashSet<>()
            : new LinkedHashSet<>(defaultApiKeyRoles);
  }
}
