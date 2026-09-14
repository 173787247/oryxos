package io.oryxos.core.policy;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.auth.Role;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 基于最小角色矩阵的授权实现（039-identity-authorization）。
 *
 * <p>设计取舍：
 *
 * <ul>
 *   <li><b>角色 → 动作集合用静态矩阵表达</b>，而不是把权限散在各调用点。矩阵集中一处，评审时一眼能看完全部 放行面，这是「最小权限」能被人工审计的前提。
 *   <li><b>VIEWER ⊆ EDITOR ⊆ ADMIN 逐级包含</b>，用 {@link EnumSet} 叠加而非三份独立清单——避免「给 EDITOR 加了能力却忘给 ADMIN
 *       加」这类只在生产暴露的矩阵漂移。
 *   <li><b>API_KEY 主体更保守</b>：即使被授予 {@link Role#ADMIN}，也不放行 {@link Action#MANAGE_MEMBERS} 与 {@link
 *       Action#MANAGE_POLICIES}。理由：Key 是长期有效、可被复制到任意环境的机器凭证；把它与人账号放在 同一权限上限，等于让一把泄露的 Key
 *       能改治理规则本身（给攻击者自己发权限）。需要这两项能力时走人账号。
 *   <li><b>角色来源不叠加</b>：主体自身已解析出角色时以它为准，只有主体未携带角色时才回落到按类别配置的默认 角色。这条是安全要点而非风格问题——若改成「并集」，一个 VIEWER
 *       账号凭同一请求里的 Key 角色即可提权到 ADMIN，属于典型的权限提升缺陷。
 * </ul>
 *
 * <p>线程安全：构造后成员全部不可变，可安全共享为单例 Bean。
 */
public final class RoleBasedAuthorizationServiceImpl implements AuthorizationService {

  /** 拒绝理由文案（集中常量：P3C 要求字面量抽常量，同时保证审计文案一致）。 */
  private static final String REASON_ANONYMOUS = "匿名请求未被授权";

  private static final String REASON_NO_ROLE = "主体未授予任何角色";

  private static final String REASON_NO_ACTION = "未指定动作";

  private static final String REASON_INSUFFICIENT_PREFIX = "角色不足以执行该动作：";

  private static final String REASON_NO_RESOURCE = "未指定资源";

  /** VIEWER：只读工作区与审计。 */
  private static final Set<Action> VIEWER_ACTIONS =
      Collections.unmodifiableSet(EnumSet.of(Action.READ_WORKSPACE, Action.READ_AUDIT));

  /** EDITOR：在 VIEWER 之上放开「干活」与资产编辑，仍不得碰边界、渠道、策略、成员。 */
  private static final Set<Action> EDITOR_ACTIONS = buildEditorActions();

  /** ADMIN：在 EDITOR 之上放开边界治理类动作。 */
  private static final Set<Action> ADMIN_ACTIONS = buildAdminActions();

  /** API Key 主体的能力上限：ADMIN 减去成员与策略两项。 */
  private static final Set<Action> API_KEY_MAX_ACTIONS = buildApiKeyMaxActions();

  private final Set<Role> defaultUserRoles;

  private final Set<Role> defaultApiKeyRoles;

  /**
   * @param defaultUserRoles 管理台账号主体未自带角色时使用的默认角色
   * @param defaultApiKeyRoles API Key 主体未自带角色时使用的默认角色
   */
  public RoleBasedAuthorizationServiceImpl(
      Set<Role> defaultUserRoles, Set<Role> defaultApiKeyRoles) {
    this.defaultUserRoles = freeze(defaultUserRoles);
    this.defaultApiKeyRoles = freeze(defaultApiKeyRoles);
  }

  @Override
  public Decision decide(Principal principal, Action action, ResourceRef resource) {
    Principal subject = principal == null ? Principal.anonymous() : principal;
    if (subject.isAnonymous()) {
      return Decision.denied(REASON_ANONYMOUS);
    }
    if (action == null) {
      return Decision.denied(REASON_NO_ACTION);
    }
    Set<Role> roles = effectiveRoles(subject);
    if (roles.isEmpty()) {
      return Decision.denied(REASON_NO_ROLE);
    }
    Set<Action> allowed = actionsFor(subject.kind(), roles);
    if (!allowed.contains(action)) {
      return Decision.denied(REASON_INSUFFICIENT_PREFIX + action + " on " + describe(resource));
    }
    return Decision.ALLOWED;
  }

  /**
   * 主体实际生效的角色：优先用主体自身携带的角色（认证阶段已解析），为空时才回落到按类别配置的默认角色。
   *
   * <p>不取并集是刻意的：{@code ApiKeyAuthFilter} 同时接受管理台 session cookie 作为凭据（018 FR-011 「session 即凭据」，管理台
   * SPA 与 REST API 同源同路径）。若取并集，账号角色与 Key 角色会互相放大， 低权限账号可借同请求里的高权限 Key 提权。
   */
  private Set<Role> effectiveRoles(Principal subject) {
    if (!subject.roles().isEmpty()) {
      return subject.roles();
    }
    return subject.kind() == Principal.Kind.API_KEY ? defaultApiKeyRoles : defaultUserRoles;
  }

  /** 按角色叠加出能力集合，再按主体类别收口（API Key 单独设上限）。 */
  private static Set<Action> actionsFor(Principal.Kind kind, Set<Role> roles) {
    Set<Action> allowed = EnumSet.noneOf(Action.class);
    if (roles.contains(Role.VIEWER) || roles.contains(Role.EDITOR) || roles.contains(Role.ADMIN)) {
      allowed.addAll(VIEWER_ACTIONS);
    }
    if (roles.contains(Role.EDITOR) || roles.contains(Role.ADMIN)) {
      allowed.addAll(EDITOR_ACTIONS);
    }
    if (roles.contains(Role.ADMIN)) {
      allowed.addAll(ADMIN_ACTIONS);
    }
    if (kind == Principal.Kind.API_KEY) {
      allowed.retainAll(API_KEY_MAX_ACTIONS);
    }
    return allowed;
  }

  private static Set<Action> buildEditorActions() {
    Set<Action> actions = EnumSet.copyOf(VIEWER_ACTIONS);
    actions.addAll(
        EnumSet.of(
            Action.RUN_AGENT,
            Action.MANAGE_AGENTS,
            Action.MANAGE_KNOWLEDGE,
            Action.MANAGE_SKILLS,
            Action.MANAGE_SESSIONS));
    return Collections.unmodifiableSet(actions);
  }

  private static Set<Action> buildAdminActions() {
    Set<Action> actions = EnumSet.copyOf(EDITOR_ACTIONS);
    actions.addAll(
        EnumSet.of(
            Action.MANAGE_WORKSPACE,
            Action.MANAGE_CHANNELS,
            Action.MANAGE_POLICIES,
            Action.MANAGE_MEMBERS));
    return Collections.unmodifiableSet(actions);
  }

  private static Set<Action> buildApiKeyMaxActions() {
    Set<Action> actions = EnumSet.copyOf(ADMIN_ACTIONS);
    actions.remove(Action.MANAGE_MEMBERS);
    actions.remove(Action.MANAGE_POLICIES);
    return Collections.unmodifiableSet(actions);
  }

  private static Set<Role> freeze(Set<Role> roles) {
    return roles == null || roles.isEmpty()
        ? Set.of()
        : Collections.unmodifiableSet(EnumSet.copyOf(roles));
  }

  /** 资源描述：{@code null} 资源归一为「未指定资源」，保证拒绝理由永远可读。 */
  private static String describe(ResourceRef resource) {
    return resource == null ? REASON_NO_RESOURCE : resource.describe();
  }

  /** 供管理台展示当前矩阵（只读视图；调用方无法篡改内部状态）。 */
  public Map<Role, Set<Action>> matrix() {
    Map<Role, Set<Action>> view = new EnumMap<>(Role.class);
    view.put(Role.VIEWER, VIEWER_ACTIONS);
    view.put(Role.EDITOR, EDITOR_ACTIONS);
    view.put(Role.ADMIN, ADMIN_ACTIONS);
    return Collections.unmodifiableMap(view);
  }
}
