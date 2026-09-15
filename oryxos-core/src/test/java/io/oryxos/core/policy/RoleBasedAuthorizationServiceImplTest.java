package io.oryxos.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.auth.Role;
import io.oryxos.core.policy.AuthorizationService.Decision;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 039 授权基座契约测试（裁决侧）：把角色矩阵的放行面与拒绝面同时钉死。
 *
 * <p>为什么逐动作穷举而不是点几个样例：授权矩阵真正的危险面是<b>补集</b>——样例只能证明「写了的规则生效」， 证明不了「没写的动作被拒」。枚举只有 11
 * 个动作，穷举成本极低，却能挡住将来「新增一个动作但忘了判」这类 默认放行的回归。
 *
 * <p>期望值在本文件里独立重写（不复用实现常量），否则测试会退化成「实现和自己比」的自证。
 */
class RoleBasedAuthorizationServiceImplTest {

  private static final String USER_ID = "alice";
  private static final String USER_DISPLAY_NAME = "Alice";
  private static final String KEY_NAME = "ci-key";
  private static final String KEY_DISPLAY_NAME = "CI Key";
  private static final String CHANNEL_NAME = "feishu";
  private static final String AGENT_NAME = "ops-agent";

  /** 断言描述（抽常量：P3C 不允许字符串字面量直接当方法参数）。 */
  private static final String ASSERT_ROW = "动作 %s 的裁决必须与冻结的角色矩阵一致";

  private static final String ASSERT_DENY_REASON = "拒绝动作 %s 必须给出可读理由（会进审计）";

  /** 拒绝理由里应出现的关键词；用于断言「理由确实在解释原因」而不是一句无信息量的常量。 */
  private static final String REASON_KEYWORD_ANONYMOUS = "匿名";

  private static final String REASON_KEYWORD_NO_ROLE = "角色";

  /** 理由里不该出现的技术噪声——出现即说明是异常串或值拼接，人读不懂、审计没法用。 */
  private static final String NOISE_NULL = "null";

  private static final String NOISE_EXCEPTION = "Exception";

  /** VIEWER 放行面：只读工作区与审计。 */
  private static final Set<Action> VIEWER_ALLOWED =
      Set.of(Action.READ_WORKSPACE, Action.READ_AUDIT);

  /** EDITOR 放行面：VIEWER + 干活 + 资产编辑。 */
  private static final Set<Action> EDITOR_ALLOWED =
      Set.of(
          Action.READ_WORKSPACE,
          Action.READ_AUDIT,
          Action.RUN_AGENT,
          Action.MANAGE_AGENTS,
          Action.MANAGE_KNOWLEDGE,
          Action.MANAGE_SKILLS,
          Action.MANAGE_SESSIONS);

  /** EDITOR 必须拒绝的边界四项：工作区边界本身、渠道、治理策略、成员。 */
  private static final Set<Action> EDITOR_DENIED =
      Set.of(
          Action.MANAGE_WORKSPACE,
          Action.MANAGE_CHANNELS,
          Action.MANAGE_POLICIES,
          Action.MANAGE_MEMBERS);

  /** ADMIN 放行全部动作。 */
  private static final Set<Action> ADMIN_ALLOWED = Set.of(Action.values());

  /** API Key 的能力上限缺口：即使给了 ADMIN 也不放行这两项。 */
  private static final Set<Action> API_KEY_DENIED =
      Set.of(Action.MANAGE_MEMBERS, Action.MANAGE_POLICIES);

  /** API Key + ADMIN 的期望放行面 = ADMIN 全集减去上面两项。 */
  private static final Set<Action> API_KEY_ADMIN_ALLOWED = buildApiKeyAdminAllowed();

  /** 未配置默认角色：主体自带角色为空即无权。矩阵边界断言一律基于它，避免默认角色干扰。 */
  private static final RoleBasedAuthorizationServiceImpl NO_DEFAULTS_SERVICE =
      new RoleBasedAuthorizationServiceImpl(Set.of(), Set.of());

  /**
   * 同一实例的接口视图：裁决断言全部经 {@link AuthorizationService} 契约调用，证明「换实现不用改调用方」。
   *
   * <p>只有矩阵展示断言下探到具体类——{@code matrix()} 不在接口契约里，属于实现的公开展示面。
   */
  private static final AuthorizationService NO_DEFAULTS = NO_DEFAULTS_SERVICE;

  /** 默认角色档：USER 未带角色时回落 VIEWER，API Key 未带角色时回落 ADMIN（提权缺陷的复现现场）。 */
  private static final AuthorizationService MIXED_DEFAULTS =
      new RoleBasedAuthorizationServiceImpl(Set.of(Role.VIEWER), Set.of(Role.ADMIN));

  /** USER 默认 EDITOR、API Key 默认 VIEWER：验证「显式角色优先于默认角色」与回落不越类别边界。 */
  private static final AuthorizationService EDITOR_DEFAULT_SERVICE =
      new RoleBasedAuthorizationServiceImpl(Set.of(Role.EDITOR), Set.of(Role.VIEWER));

  // ---------------------------------------------------------------- 行为 1：匿名一律拒

  @Test
  @DisplayName("匿名主体_全部动作一律拒_且理由可读不含技术噪声")
  void anonymousIsDeniedForEveryActionWithReadableReason() {
    Principal anonymous = Principal.anonymous();

    for (Action action : Action.values()) {
      Decision decision = NO_DEFAULTS.decide(anonymous, action, ResourceRef.workspace());

      // 为什么逐动作断言：匿名放行是授权系统最严重的失效形态，必须证明 11 个动作一个都没漏，
      // 而不是「我测过的那个被拒了」。
      assertThat(decision.allowed()).as(ASSERT_ROW, action).isFalse();
      // 为什么断言理由非空且提到「匿名」：拒绝理由要进审计，审计读者必须能一眼看出
      // 这是未认证访问（用于统计探测行为），而不是一句无信息量的「拒绝」。
      assertThat(decision.reason())
          .as(ASSERT_DENY_REASON, action)
          .isNotBlank()
          .contains(REASON_KEYWORD_ANONYMOUS);
      // 为什么断言无技术噪声：null/异常串进了审计文案，说明这是值拼接或异常兜底，人读不懂。
      assertThat(decision.reason()).as(ASSERT_DENY_REASON, action).doesNotContain(NOISE_NULL);
      assertThat(decision.reason()).as(ASSERT_DENY_REASON, action).doesNotContain(NOISE_EXCEPTION);
    }
  }

  @Test
  @DisplayName("伪造的匿名主体_自带 ADMIN 角色_仍被拒_匿名门在角色门之前")
  void forgedAnonymousPrincipalWithAdminRoleIsStillDenied() {
    // 用规范构造器伪造一个「ANONYMOUS 但带 ADMIN 角色」的主体：模拟上游错误地给匿名主体塞了角色。
    Principal forged =
        new Principal(Principal.Kind.ANONYMOUS, USER_ID, USER_DISPLAY_NAME, Set.of(Role.ADMIN));

    Decision decision =
        NO_DEFAULTS.decide(forged, Action.MANAGE_MEMBERS, ResourceRef.member(USER_ID));

    // 为什么断言仍被拒：身份类别（未认证）必须压过角色集合。若实现先看角色再看 isAnonymous，
    // 这里就会放行——这是「认证边界」被角色数据绕过的典型提权路径。
    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).contains(REASON_KEYWORD_ANONYMOUS);
  }

  @Test
  @DisplayName("主体为 null_归一为匿名拒绝_不抛 NPE")
  void nullPrincipalIsDeniedInsteadOfThrowing() {
    Decision decision = NO_DEFAULTS.decide(null, Action.READ_WORKSPACE, ResourceRef.workspace());

    // 为什么断言拒绝而不是抛异常：上游漏传主体时，抛异常会把拒绝路径变成 500——既暴露内部结构，
    // 又让这次未授权访问不进审计。失败方向必须朝「拒绝」。
    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).isNotBlank().contains(REASON_KEYWORD_ANONYMOUS);
  }

  // ---------------------------------------------------------------- 行为 2：零角色 USER

  @Test
  @DisplayName("USER 未授予角色_未配置默认角色时全部动作一律拒")
  void userWithoutRolesIsDeniedForEveryAction() {
    Principal roleless = Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of());

    // 为什么断言空角色 = 无权：这是「最小权限」的默认值。主体对象存在（已认证）不等于有权限，
    // 否则任何能通过认证的账号都自动拿到全套能力。
    assertMatrixRow(NO_DEFAULTS, roleless, Set.of());
  }

  @Test
  @DisplayName("USER 角色为 null_等同零角色_仍然全拒")
  void userWithNullRolesBehavesLikeRoleless() {
    Principal nullRoles = Principal.user(USER_ID, USER_DISPLAY_NAME, null);

    // 为什么单独断言 null：null 与空集在授权语义上必须等价（都是无权）。若 null 走了「未配置 → 放行」
    // 的分支，就成了一个只有构造器写法不同就能绕过的后门。
    assertMatrixRow(NO_DEFAULTS, nullRoles, Set.of());
    assertThat(nullRoles.roles()).isEmpty();
  }

  // ---------------------------------------------------------------- 行为 3：VIEWER 边界

  @Test
  @DisplayName("VIEWER_只放行只读工作区与审计_其余九个动作全拒")
  void viewerOnlyGetsReadOnlyActions() {
    Principal viewer = Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.VIEWER));

    assertMatrixRow(NO_DEFAULTS, viewer, VIEWER_ALLOWED);

    // 为什么单独点名 RUN_AGENT：只读档「能看不能干」的分界线就在这里，最容易被顺手放宽。
    assertThat(
            NO_DEFAULTS.decide(viewer, Action.RUN_AGENT, ResourceRef.agent(AGENT_NAME)).allowed())
        .isFalse();
  }

  // ---------------------------------------------------------------- 行为 4：EDITOR 边界

  @Test
  @DisplayName("EDITOR_放行干活与资产编辑_但四项边界动作必须拒绝")
  void editorGetsWorkActionsButNotBoundaryActions() {
    Principal editor = Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.EDITOR));

    assertMatrixRow(NO_DEFAULTS, editor, EDITOR_ALLOWED);

    // 为什么把四项边界逐个点名（即使上面的穷举已覆盖）：这是 EDITOR 与 ADMIN 的分界，是矩阵里
    // 最需要「看一眼就确认」的地方。EDITOR 若能改策略/成员，等于自己给自己发权限。
    for (Action boundary : EDITOR_DENIED) {
      assertThat(NO_DEFAULTS.decide(editor, boundary, ResourceRef.workspace()).allowed())
          .as(ASSERT_ROW, boundary)
          .isFalse();
    }
  }

  @Test
  @DisplayName("EDITOR_逐级包含VIEWER_放行面是VIEWER的超集")
  void editorSupersetOfViewer() {
    Principal viewer = Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.VIEWER));
    Principal editor = Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.EDITOR));

    // 为什么断言包含关系而不是分别列两份清单：逐级包含是「给 EDITOR 加了能力不会忘给 ADMIN」的
    // 结构性保证；用包含关系表达，矩阵漂移会直接在这里炸出来。
    for (Action action : VIEWER_ALLOWED) {
      assertThat(NO_DEFAULTS.decide(viewer, action, ResourceRef.workspace()).allowed())
          .as(ASSERT_ROW, action)
          .isTrue();
      assertThat(NO_DEFAULTS.decide(editor, action, ResourceRef.workspace()).allowed())
          .as(ASSERT_ROW, action)
          .isTrue();
    }
  }

  // ---------------------------------------------------------------- 行为 5：ADMIN 全集

  @Test
  @DisplayName("ADMIN_放行全部动作_且允许时理由为 null")
  void adminGetsEveryAction() {
    Principal admin = Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.ADMIN));

    assertMatrixRow(NO_DEFAULTS, admin, ADMIN_ALLOWED);

    // 为什么断言允许时 reason 为 null：允许是默认路径，若它也带理由，审计里会塞满无意义的「已允许」，
    // 调用方也不得不多一次判空。
    assertThat(NO_DEFAULTS.decide(admin, Action.MANAGE_POLICIES, ResourceRef.policy()).reason())
        .isNull();
  }

  @Test
  @DisplayName("ADMIN_逐级包含EDITOR_放行面是EDITOR的超集")
  void adminSupersetOfEditor() {
    Principal admin = Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.ADMIN));

    for (Action action : EDITOR_ALLOWED) {
      assertThat(NO_DEFAULTS.decide(admin, action, ResourceRef.workspace()).allowed())
          .as(ASSERT_ROW, action)
          .isTrue();
    }
  }

  // ---------------------------------------------------------------- 行为 6：API Key 上限

  @Test
  @DisplayName("API Key_给ADMIN角色_仍拒绝管理成员与管理策略")
  void apiKeyWithAdminRoleStillCannotManageMembersOrPolicies() {
    Principal key = Principal.apiKey(KEY_NAME, KEY_DISPLAY_NAME, Set.of(Role.ADMIN));

    // 为什么断言拒绝这两项：Key 是长期有效、可被复制到任意环境的机器凭证。若它能改成员与治理策略，
    // 一把泄露的 Key 就能给自己发权限（改成员）或关掉约束自己的规则（改策略）——失陷后无法收敛。
    assertThat(
            NO_DEFAULTS.decide(key, Action.MANAGE_MEMBERS, ResourceRef.member(USER_ID)).allowed())
        .isFalse();
    assertThat(NO_DEFAULTS.decide(key, Action.MANAGE_POLICIES, ResourceRef.policy()).allowed())
        .isFalse();

    // 反向断言：上限只砍这两项，不是把 Key 一刀切禁掉——否则「Key + ADMIN」这个配置形同虚设。
    assertThat(
            NO_DEFAULTS
                .decide(key, Action.MANAGE_CHANNELS, ResourceRef.channel(CHANNEL_NAME))
                .allowed())
        .isTrue();
  }

  @Test
  @DisplayName("API Key_能力上限恰好是ADMIN全集减去两项_不多不少")
  void apiKeyCapIsExactlyAdminMinusTwoActions() {
    Principal userAdmin = Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.ADMIN));
    Principal keyAdmin = Principal.apiKey(KEY_NAME, KEY_DISPLAY_NAME, Set.of(Role.ADMIN));

    // 为什么逐动作对比人账号与 Key：证明「收口」是精确的两项缺口，而不是顺手把 Key 的整体能力
    // 收窄了（那会让 API 集成方莫名其妙地少权限，属于另一种回归）。
    for (Action action : Action.values()) {
      Decision forUser = NO_DEFAULTS.decide(userAdmin, action, ResourceRef.workspace());
      Decision forKey = NO_DEFAULTS.decide(keyAdmin, action, ResourceRef.workspace());
      // USER 的 ADMIN 是全集：MANAGE_MEMBERS/MANAGE_POLICIES 只对 Key 收口，人账号必须仍然放行——
      // 否则「需要这两项能力时走人账号」这条设计出路就被堵死了（收口变成一刀切）。
      boolean expectedForUser = ADMIN_ALLOWED.contains(action);
      boolean expectedForKey = API_KEY_ADMIN_ALLOWED.contains(action);

      assertThat(forUser.allowed()).as(ASSERT_ROW, action).isEqualTo(expectedForUser);
      assertThat(forKey.allowed()).as(ASSERT_ROW, action).isEqualTo(expectedForKey);
    }

    // 人账号持有 ADMIN 时这两项必须仍放行——否则「需要改成员/策略时走人账号」这条设计出路就被堵死了。
    assertThat(userAdmin.hasRole(Role.ADMIN)).isTrue();
    assertThat(
            NO_DEFAULTS
                .decide(userAdmin, Action.MANAGE_MEMBERS, ResourceRef.member(USER_ID))
                .allowed())
        .isTrue();
    assertThat(
            NO_DEFAULTS.decide(userAdmin, Action.MANAGE_POLICIES, ResourceRef.policy()).allowed())
        .isTrue();
  }

  @Test
  @DisplayName("API Key_零角色_回落到默认Key角色_并同样受两项上限约束")
  void rolelessApiKeyFallsBackToDefaultApiKeyRolesAndIsStillCapped() {
    Principal rolelessKey = Principal.apiKey(KEY_NAME, KEY_DISPLAY_NAME);

    // 为什么断言回落生效：MIXED_DEFAULTS 里 API Key 默认角色是 ADMIN，零角色 Key 应当拿到
    // 「ADMIN 减去两项」这套能力——证明 apiKeyRoles 确实是 Key 的授权来源，而不是被忽略。
    assertThat(
            MIXED_DEFAULTS
                .decide(rolelessKey, Action.MANAGE_CHANNELS, ResourceRef.channel(CHANNEL_NAME))
                .allowed())
        .isTrue();
    // 为什么同时断言上限仍然生效：默认角色不能成为绕过 API_KEY_MAX_ACTIONS 的旁路
    // （若实现把「默认角色」当成另一条独立分支直接返回，这里就会放开）。
    assertThat(
            MIXED_DEFAULTS
                .decide(rolelessKey, Action.MANAGE_POLICIES, ResourceRef.policy())
                .allowed())
        .isFalse();
    assertThat(
            MIXED_DEFAULTS
                .decide(rolelessKey, Action.MANAGE_MEMBERS, ResourceRef.member(USER_ID))
                .allowed())
        .isFalse();
  }

  @Test
  @DisplayName("API Key_零角色_未配置默认Key角色时全拒")
  void rolelessApiKeyWithoutDefaultsIsDeniedEverywhere() {
    Principal rolelessKey = Principal.apiKey(KEY_NAME, KEY_DISPLAY_NAME);

    // 为什么断言全拒：认证通过（是一把合法 Key）不等于有权限。默认零角色必须落到「全拒」，
    // 否则任何合法 Key 都自动获得基础只读能力，等于没有授权层。
    assertMatrixRow(NO_DEFAULTS, rolelessKey, Set.of());
  }

  // ---------------------------------------------------------------- 行为 7：ALLOW_ALL

  @Test
  @DisplayName("ALLOW_ALL_对任意主体与动作恒放行_理由为 null")
  void allowAllAlwaysAllowsEverything() {
    Principal[] principals = {
      Principal.anonymous(), Principal.apiKey(KEY_NAME, KEY_DISPLAY_NAME), null
    };

    for (Principal principal : principals) {
      for (Action action : Action.values()) {
        Decision decision = AuthorizationService.ALLOW_ALL.decide(principal, action, null);

        // 为什么断言恒放行：这是「授权开关默认关 = 零行为变化」承诺的锚点。未启用授权时注入
        // ALLOW_ALL，任何一处返回 false 都会让默认部署出现「以前能用现在 403」的破坏性变化。
        assertThat(decision.allowed()).as(ASSERT_ROW, action).isTrue();
        // 为什么断言 reason 为 null：调用方永远不需要判空/读理由，ALLOW_ALL 是真正的空实现。
        assertThat(decision.reason()).isNull();
      }
    }
  }

  @Test
  @DisplayName("ALLOW_ALL_动作或资源为null也不抛异常_直接放行")
  void allowAllToleratesNullActionAndResource() {
    // 为什么：默认关时调用方甚至可能还没接好参数（半接线的中间态）。ALLOW_ALL 必须比真实实现
    // 更宽容——它的唯一职责是「不产生任何行为变化」，在这里抛异常等于把开关默认档变成故障源。
    assertThat(AuthorizationService.ALLOW_ALL.decide(null, null, null).allowed()).isTrue();
    assertThat(AuthorizationService.ALLOW_ALL.isAllowed(null, null, null)).isTrue();
  }

  @Test
  @DisplayName("Decision_工厂方法_拒绝理由永不为空_允许不带理由")
  void decisionFactoriesNeverProduceBlankReasons() {
    assertThat(Decision.ALLOWED.allowed()).isTrue();
    assertThat(Decision.ALLOWED.reason()).isNull();

    assertThat(Decision.denied(REASON_KEYWORD_NO_ROLE).allowed()).isFalse();
    assertThat(Decision.denied(REASON_KEYWORD_NO_ROLE).reason()).isEqualTo(REASON_KEYWORD_NO_ROLE);

    // 为什么断言 null/空白理由被兜底：拒绝理由要进审计，空文案会让审计记录变成「有人被拒了，
    // 但不知道算什么」。调用方漏传理由不该毁掉审计可用性。
    assertThat(Decision.denied(null).reason()).isNotBlank();
    assertThat(Decision.denied("   ").reason()).isNotBlank();
  }

  // ---------------------------------------------------------------- 提权缺陷：角色不叠加

  @Test
  @DisplayName("主体自带角色时不与默认角色叠加_VIEWER账号不能借ADMIN级Key提权")
  void principalRolesAreNotUnionedWithDefaultRoles() {
    // 场景：同一请求里管理台 session（VIEWER 账号）与一把 ADMIN 级 API Key 同时存在——
    // ApiKeyAuthFilter 两种凭据都接受，这个组合在真实部署里是可达的。
    Principal viewerAccount = Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.VIEWER));

    Decision managePolicies =
        MIXED_DEFAULTS.decide(viewerAccount, Action.MANAGE_POLICIES, ResourceRef.policy());
    Decision manageMembers =
        MIXED_DEFAULTS.decide(viewerAccount, Action.MANAGE_MEMBERS, ResourceRef.member(USER_ID));
    Decision runAgent =
        MIXED_DEFAULTS.decide(viewerAccount, Action.RUN_AGENT, ResourceRef.agent(AGENT_NAME));

    // 为什么断言 MANAGE_POLICIES 被拒：若实现把「主体角色」与「类别默认角色」取并集，
    // 这里会因为 Key 的默认 ADMIN 而放行——低权账号凭同请求里的高权 Key 提权到 ADMIN，
    // 这是本轮修掉的权限提升缺陷，必须由测试长期守住。
    assertThat(managePolicies.allowed()).isFalse();
    // 为什么连 MANAGE_MEMBERS 一起断言：它是提权链的终点（改成员 = 给自己发角色），
    // 只要它被拒，提权路径就无法闭环。
    assertThat(manageMembers.allowed()).isFalse();
    // 为什么还断言 RUN_AGENT 被拒：证明生效的确实是 VIEWER（默认 VIEWER 账号档），
    // 而不是「实现退化成一律拒绝」——后者也能让上面两条通过，但那不是正确语义。
    assertThat(runAgent.allowed()).isFalse();
    // 为什么断言 READ_WORKSPACE 放行：VIEWER 档真的生效了——排除「拒绝一切」的假阳性。
    assertThat(
            MIXED_DEFAULTS
                .decide(viewerAccount, Action.READ_WORKSPACE, ResourceRef.workspace())
                .allowed())
        .isTrue();
  }

  @Test
  @DisplayName("显式角色优先于默认角色_主体带VIEWER则不再回落EDITOR默认")
  void explicitRolesTakePrecedenceOverDefaults() {
    Principal viewerAccount = Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.VIEWER));

    // 为什么断言 RUN_AGENT 被拒：默认角色是「主体没带角色时的兜底」，不是「额外加成」。
    // 若默认 EDITOR 被叠加，显式声明的 VIEWER 就没意义了——声明的角色反而不如没声明。
    assertThat(
            EDITOR_DEFAULT_SERVICE
                .decide(viewerAccount, Action.RUN_AGENT, ResourceRef.agent(AGENT_NAME))
                .allowed())
        .isFalse();
    assertThat(
            EDITOR_DEFAULT_SERVICE
                .decide(viewerAccount, Action.READ_AUDIT, ResourceRef.audit())
                .allowed())
        .isTrue();
  }

  @Test
  @DisplayName("零角色USER_回落到默认USER角色_但回落不越过类别边界")
  void rolelessUserFallsBackToDefaultUserRoles() {
    Principal rolelessAccount = Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of());

    // 为什么断言回落生效：部署方配置了 defaultUserRoles 就是明确表态「本部署的账号默认这个档」，
    // 忽略它会让配置静默失效（管理员以为配了、实际没生效，是最难排查的一类问题）。
    assertThat(
            EDITOR_DEFAULT_SERVICE
                .decide(rolelessAccount, Action.RUN_AGENT, ResourceRef.agent(AGENT_NAME))
                .allowed())
        .isTrue();
    // 为什么断言默认 EDITOR 仍拿不到边界动作：回落走的是同一条矩阵与收口逻辑，
    // 不能因为「来自默认配置」就绕过 EDITOR 的边界限制。
    assertThat(
            EDITOR_DEFAULT_SERVICE
                .decide(rolelessAccount, Action.MANAGE_POLICIES, ResourceRef.policy())
                .allowed())
        .isFalse();
  }

  // ---------------------------------------------------------------- 拒绝理由与资源维度

  @Test
  @DisplayName("拒绝理由_点名动作与资源_审计能定位到「谁想做什么被拒」")
  void denialReasonNamesActionAndResource() {
    Principal editor = Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.EDITOR));

    Decision decision = NO_DEFAULTS.decide(editor, Action.MANAGE_POLICIES, ResourceRef.policy());

    // 为什么断言理由里出现动作名与资源类型：审计要能回答「哪个动作被拒、作用在什么上」。
    // 只写「无权限」的记录，事后无法区分「有人试图改治理策略」和「有人只是点错了页面」。
    assertThat(decision.reason()).contains(Action.MANAGE_POLICIES.name());
    assertThat(decision.reason()).contains(ResourceRef.TYPE_POLICY);
    assertThat(decision.reason()).doesNotContain(NOISE_NULL);
  }

  @Test
  @DisplayName("资源为null_拒绝理由仍然可读_不出现null字样")
  void nullResourceStillYieldsReadableReason() {
    Principal editor = Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.EDITOR));

    Decision decision = NO_DEFAULTS.decide(editor, Action.MANAGE_POLICIES, null);

    // 为什么：调用点可能还没接好资源（增量接线期），此时理由是审计里唯一的线索。
    // 出现 "null" 说明这是对象 toString 拼接，人读不懂。
    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).isNotBlank().doesNotContain(NOISE_NULL);
  }

  @Test
  @DisplayName("动作为null_拒绝并给可读理由_不抛NPE也不默认放行")
  void nullActionIsDeniedWithReadableReason() {
    Principal admin = Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.ADMIN));

    Decision decision = NO_DEFAULTS.decide(admin, null, ResourceRef.workspace());

    // 为什么是拒绝而不是放行：动作未知意味着「要做什么」没确定，放行等于给一个未定义操作开绿灯。
    // 为什么是拒绝而不是抛异常：同上，拒绝还能留下一条审计。
    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).isNotBlank().doesNotContain(NOISE_NULL);
  }

  @Test
  @DisplayName("当前矩阵与资源无关_同一动作换资源不改变裁决")
  void decisionIsResourceAgnosticInCurrentScope() {
    Principal admin = Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.ADMIN));
    Principal editor = Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.EDITOR));

    // 为什么把这个「限制」写成断言：039 的矩阵是角色 × 动作，资源只进拒绝理由、不参与裁决。
    // 钉住它有两个作用：一是明确当前能力边界（别误以为传了 agent 名就做了资源级隔离），
    // 二是将来真要做资源级规则时，这条断言会失败并强制评审——而不是悄悄地半实现。
    assertThat(
            NO_DEFAULTS
                .decide(admin, Action.MANAGE_CHANNELS, ResourceRef.channel(CHANNEL_NAME))
                .allowed())
        .isTrue();
    assertThat(NO_DEFAULTS.decide(admin, Action.MANAGE_CHANNELS, ResourceRef.workspace()).allowed())
        .isTrue();
    assertThat(
            NO_DEFAULTS
                .decide(editor, Action.MANAGE_CHANNELS, ResourceRef.channel(CHANNEL_NAME))
                .allowed())
        .isFalse();
    assertThat(
            NO_DEFAULTS.decide(editor, Action.MANAGE_CHANNELS, ResourceRef.workspace()).allowed())
        .isFalse();
  }

  // ---------------------------------------------------------------- matrix() 只读视图

  @Test
  @DisplayName("matrix_三档角色都在_EDITOR行恰好含七项且不含四项边界")
  void matrixExposesExactRoleBoundaries() {
    Set<Action> editorRow = NO_DEFAULTS_SERVICE.matrix().get(Role.EDITOR);

    // 为什么断言矩阵内容而不是只信 decide：matrix() 是管理台展示授权面的数据源。
    // 若它和 decide 用的矩阵不是同一份，管理台会展示一个「看起来更小/更大」的权限面，
    // 管理员据此做的授权决策就是错的。
    assertThat(NO_DEFAULTS_SERVICE.matrix().keySet())
        .containsExactlyInAnyOrder(Role.VIEWER, Role.EDITOR, Role.ADMIN);
    assertThat(NO_DEFAULTS_SERVICE.matrix().get(Role.VIEWER))
        .containsExactlyInAnyOrderElementsOf(VIEWER_ALLOWED);
    assertThat(editorRow).containsExactlyInAnyOrderElementsOf(EDITOR_ALLOWED);
    assertThat(editorRow).doesNotContainAnyElementsOf(EDITOR_DENIED);
    assertThat(NO_DEFAULTS_SERVICE.matrix().get(Role.ADMIN))
        .containsExactlyInAnyOrderElementsOf(ADMIN_ALLOWED);
  }

  @Test
  @DisplayName("matrix_返回只读视图_外部无法篡改矩阵")
  void matrixIsReadOnly() {
    // 为什么断言不可写：矩阵一旦被外部改动，整个授权面就跟着变，而且这种改动不经过任何配置入口、
    // 不留痕迹。它是「能被人工审计」的前提。
    assertThatThrownBy(() -> NO_DEFAULTS_SERVICE.matrix().put(Role.VIEWER, ADMIN_ALLOWED))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> NO_DEFAULTS_SERVICE.matrix().get(Role.VIEWER).add(Action.MANAGE_MEMBERS))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> NO_DEFAULTS_SERVICE.matrix().remove(Role.ADMIN))
        .isInstanceOf(UnsupportedOperationException.class);

    // 证明上面的失败尝试没有改变内部状态。
    assertThat(NO_DEFAULTS_SERVICE.matrix().get(Role.VIEWER))
        .containsExactlyInAnyOrderElementsOf(VIEWER_ALLOWED);
  }

  @Test
  @DisplayName("构造参数为null_不抛异常_回落为无默认角色即全拒")
  void nullConstructorArgsDegradeToDeny() {
    AuthorizationService service = new RoleBasedAuthorizationServiceImpl(null, null);
    Principal roleless = Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of());

    // 为什么断言「不抛 + 全拒」：配置缺失时失败方向必须朝拒绝而不是朝放行。
    // 若 null 被当成「没配置 → 不限制」，一次漏配就等于关掉了整个授权层，且启动期毫无提示。
    assertThat(service.decide(roleless, Action.READ_WORKSPACE, ResourceRef.workspace()).allowed())
        .isFalse();
    assertThat(
            service.decide(roleless, Action.MANAGE_MEMBERS, ResourceRef.member(USER_ID)).allowed())
        .isFalse();
  }

  // ---------------------------------------------------------------- 辅助

  /**
   * 逐动作穷举一行矩阵：断言放行面与期望集合完全一致，且每个被拒动作都带可读理由。
   *
   * @param service 被测服务
   * @param principal 被测主体
   * @param expectedAllowed 期望放行的动作集合（本文件独立重写，不复用实现常量）
   */
  private static void assertMatrixRow(
      AuthorizationService service, Principal principal, Set<Action> expectedAllowed) {
    for (Action action : Action.values()) {
      Decision decision = service.decide(principal, action, ResourceRef.workspace());
      boolean expected = expectedAllowed.contains(action);

      // 为什么同时断言 allowed 与 reason：只断言 allowed 会漏掉「拒绝理由为空」这类
      // 不影响裁决结果、却让审计失效的实现退化。
      assertThat(decision.allowed()).as(ASSERT_ROW, action).isEqualTo(expected);
      if (expected) {
        assertThat(decision.reason()).as(ASSERT_ROW, action).isNull();
      } else {
        assertThat(decision.reason()).as(ASSERT_DENY_REASON, action).isNotBlank();
      }
    }
  }

  private static Set<Action> buildApiKeyAdminAllowed() {
    Set<Action> actions = EnumSet.allOf(Action.class);
    actions.removeAll(API_KEY_DENIED);
    return Collections.unmodifiableSet(actions);
  }
}
