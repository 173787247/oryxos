package io.oryxos.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 039 授权基座契约测试（主体侧）：把「谁在请求」钉成可断言的不变量。
 *
 * <p>为什么这组测试的重点不是 happy path：授权系统最常见的失效形态不是「判错」，而是「没判」——主体对象一旦允许可变、 允许 null 语义漂移，上层三个决策点（API / 管理台
 * / 运行时）就会各自解释一遍，漏掉的那一处默认放行。所以下面断言的 核心是：角色集合不可变、null 只往保守方向归一、零角色主体拿不到任何权限。
 */
class PrincipalTest {

  private static final String USER_ID = "alice";
  private static final String USER_DISPLAY_NAME = "Alice";
  private static final String KEY_NAME = "ci-key";
  private static final String KEY_DISPLAY_NAME = "CI Key";

  /** 匿名主体在审计里出现的固定 id——值本身是契约的一部分（便于聚合「未认证访问」）。 */
  private static final String ANONYMOUS_AUDIT_ID = "anonymous";

  @Test
  @DisplayName("匿名主体_是一等主体且不携带任何角色")
  void anonymousSubjectIsFirstClassAndRoleless() {
    Principal principal = Principal.anonymous();

    // 为什么断言 isAnonymous()：拒绝路径要能先判出「这是匿名」再给可读理由；
    // 若匿名用 null 表示，决策点就得靠空值判断，漏判一次就是匿名放行。
    assertThat(principal.isAnonymous()).isTrue();
    assertThat(principal.kind()).isEqualTo(Principal.Kind.ANONYMOUS);
    // 为什么断言 id 固定：审计表里「未认证访问」必须能聚合到同一个主体名，否则无法统计探测行为。
    assertThat(principal.id()).isEqualTo(ANONYMOUS_AUDIT_ID);
    // 为什么断言零角色：匿名主体不能走「有角色就放行」的兜底路径提权。
    assertThat(principal.roles()).isEmpty();
    assertThat(principal.hasRole(Role.ADMIN)).isFalse();
  }

  @Test
  @DisplayName("匿名主体_值语义相等_便于审计去重")
  void anonymousSubjectIsValueEqual() {
    // 为什么：record 值语义让「同一个匿名主体」在审计与测试里可比对（不同实例、同一条记录口径）。
    assertThat(Principal.anonymous()).isEqualTo(Principal.anonymous());
    assertThat(Principal.anonymous()).hasSameHashCodeAs(Principal.anonymous());
  }

  @Test
  @DisplayName("roles_返回不可变集合_任何写操作抛 UnsupportedOperationException")
  void rolesAreImmutable() {
    Principal principal =
        Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.VIEWER, Role.EDITOR));

    // 为什么断言抛异常而不是「改了也不生效」：主体在 Filter → Controller → 授权点之间被多方共享，
    // 若 roles() 可写，链路上任意一处都能静默给自己加 ADMIN——这是提权，不是数据不一致。
    assertThatThrownBy(() -> principal.roles().add(Role.ADMIN))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> principal.roles().remove(Role.VIEWER))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> principal.roles().clear())
        .isInstanceOf(UnsupportedOperationException.class);

    // 为什么再断言一次内容：证明上面三次失败尝试确实没有改动主体状态（拒绝写入 ≠ 写入后被忽略）。
    assertThat(principal.roles()).containsExactlyInAnyOrder(Role.VIEWER, Role.EDITOR);
  }

  @Test
  @DisplayName("roles_是构造时刻的快照_调用方之后改自己的集合不影响主体")
  void rolesSnapshotIsDetachedFromCallerSet() {
    Set<Role> callerOwned = new LinkedHashSet<>(List.of(Role.VIEWER));
    Principal principal = Principal.user(USER_ID, USER_DISPLAY_NAME, callerOwned);

    // 调用方在构造之后继续持有并修改原集合——模拟上游服务复用同一个 Set 变量的常见写法。
    callerOwned.add(Role.ADMIN);

    // 为什么断言没被影响：主体必须是「某个认证时刻的授权快照」。若共享同一个集合引用，
    // 一次与认证无关的 add 就能给已认证主体悄悄提权，且没有任何日志留下。
    assertThat(principal.roles()).containsExactly(Role.VIEWER);
    assertThat(principal.hasRole(Role.ADMIN)).isFalse();
  }

  @Test
  @DisplayName("kind 为 null_归一成 ANONYMOUS 而非 USER_失败方向朝保守")
  void nullKindNormalizesToAnonymous() {
    Principal principal = new Principal(null, USER_ID, USER_DISPLAY_NAME, Set.of(Role.ADMIN));

    // 为什么归一成 ANONYMOUS：上游漏赋值时，实现选择不抛异常（避免把整条请求打挂），
    // 但必须落到最保守的一档。这里带 ADMIN 角色仍然归一为匿名，正是「未知身份 ≠ 可信身份」的落点。
    assertThat(principal.kind()).isEqualTo(Principal.Kind.ANONYMOUS);
    assertThat(principal.isAnonymous()).isTrue();
  }

  @Test
  @DisplayName("roles 为 null_归一成空集_不抛 NPE")
  void nullRolesNormalizeToEmptySet() {
    Principal principal = Principal.user(USER_ID, USER_DISPLAY_NAME, null);

    // 为什么归一而不是抛异常：null 与「没有角色」在授权语义上等价（都是无权），归一后 hasRole 一律 false；
    // 若这里抛 NPE，拒绝路径会变成 500，反而把内部结构暴露给未授权调用方。
    assertThat(principal.roles()).isEmpty();
    assertThat(principal.hasRole(Role.VIEWER)).isFalse();
  }

  @Test
  @DisplayName("roles 重复_输出按去重后的基数_且不钉死迭代顺序")
  void duplicateRolesAreCollapsedToDistinctSet() {
    Set<Role> withDuplicates =
        new LinkedHashSet<>(List.of(Role.EDITOR, Role.EDITOR, Role.VIEWER, Role.EDITOR));
    Principal principal = Principal.user(USER_ID, USER_DISPLAY_NAME, withDuplicates);

    // 为什么断言基数 2：入参类型是 Set，结构上已不可能有重复；这条断言守的是「实现不得换成
    // 允许重复的载体」——审计里出现重复角色会让「这个人到底几档」产生歧义。
    assertThat(principal.roles()).hasSize(2);
    // 为什么用 containsExactlyInAnyOrder 而不是 containsExactly：集合顺序不属于冻结契约，
    // 钉死顺序属于过度规约（实现换成别的 Set 实现就会误报）；这里只钉内容。
    assertThat(principal.roles()).containsExactlyInAnyOrder(Role.VIEWER, Role.EDITOR);
  }

  @Test
  @DisplayName("roles 含 null 元素_抛 NPE_快速失败而非静默丢弃")
  void nullRoleElementFailsFast() {
    // 注意：这里必须用允许 null 的集合构造方式。若写 Set.of(Role.VIEWER, null)，
    // 是 Set.of 自己先抛 NPE，测不到 Principal 的行为。
    Set<Role> withNullElement = new LinkedHashSet<>(Arrays.asList(Role.VIEWER, null));

    // 为什么断言抛 NPE：实现走 EnumSet.copyOf，元素为 null 时 NPE——这是 fail-fast。
    // 相反的处理（静默丢掉 null 继续构造）会让「角色集合里混进了 null」这种上游 bug 无声通过，
    // 之后排查「权限被谁改过」时没有任何线索。
    assertThatThrownBy(() -> Principal.user(USER_ID, USER_DISPLAY_NAME, withNullElement))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("空角色集合_无论来源都不可授权")
  void emptyRolesAreNeverAuthorizable() {
    // 为什么把「匿名」与「零角色」放在一条断言里：契约要求两者拒绝口径一致，
    // 收敛成一个判断可避免三个决策点各写一遍、漏写一处就是一条绕过路径。
    assertThat(Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of()).isAuthorizable()).isFalse();
    assertThat(Principal.anonymous().isAuthorizable()).isFalse();
    // 有角色的 USER 才可授权——反向断言，避免 isAuthorizable 退化成恒 false 也能过测试。
    assertThat(Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.VIEWER)).isAuthorizable())
        .isTrue();
  }

  @Test
  @DisplayName("apiKey_默认工厂零角色_角色只能显式授予")
  void apiKeyDefaultsToNoRoleAndNeedsExplicitGrant() {
    Principal defaultKey = Principal.apiKey(KEY_NAME, KEY_DISPLAY_NAME);

    // 为什么断言非匿名：API Key 是已认证的机器主体，与匿名必须区分开（否则拒绝理由会误导排障）。
    assertThat(defaultKey.isAnonymous()).isFalse();
    assertThat(defaultKey.kind()).isEqualTo(Principal.Kind.API_KEY);
    // 为什么断言零角色：Key 是机器凭据，默认无任何权限是「最小权限」的默认值；
    // 权限只能来自显式授予，不能来自「这是一把合法 Key」这件事本身。
    assertThat(defaultKey.roles()).isEmpty();
    assertThat(defaultKey.isAuthorizable()).isFalse();

    Principal grantedKey = Principal.apiKey(KEY_NAME, KEY_DISPLAY_NAME, Set.of(Role.EDITOR));
    // 为什么反向断言显式授予必须生效：否则 API_KEY 分支永远只能被拒，Key 认证形同白做。
    assertThat(grantedKey.roles()).containsExactly(Role.EDITOR);
    assertThat(grantedKey.hasRole(Role.EDITOR)).isTrue();
  }

  @Test
  @DisplayName("hasRole_传 null 返 false_不抛异常")
  void hasRoleWithNullReturnsFalse() {
    Principal principal = Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.VIEWER));

    // 为什么：授权判定里抛异常会把「拒绝」变成 500——既打断审计写入，也把内部结构暴露给未授权方。
    // 未知角色只能解释成「没有这个角色」。
    assertThat(principal.hasRole(null)).isFalse();
  }

  @Test
  @DisplayName("user 工厂_字段原样保留且具备 record 值语义")
  void userFactoryKeepsFieldsAndIsValueEqual() {
    Principal principal =
        Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.VIEWER, Role.ADMIN));

    assertThat(principal.kind()).isEqualTo(Principal.Kind.USER);
    assertThat(principal.id()).isEqualTo(USER_ID);
    assertThat(principal.displayName()).isEqualTo(USER_DISPLAY_NAME);
    // 为什么断言值语义：审计/测试对「同一主体」的比对依赖 equals，引用语义会让同一账号的两次记录不可聚合。
    assertThat(principal)
        .isEqualTo(Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.VIEWER, Role.ADMIN)));
    assertThat(principal)
        .hasSameHashCodeAs(
            Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.ADMIN, Role.VIEWER)));
  }

  @Test
  @DisplayName("describe_API Key 只出名称不出展示名_守住审计脱敏")
  void describeExposesStableIdOnly() {
    Principal principal = Principal.apiKey(KEY_NAME, KEY_DISPLAY_NAME);

    // 为什么断言等值于 "kind:id"：主体描述会进审计日志。用稳定 id 而非展示名，
    // 是因为展示名可含人名/邮箱等个人信息；Key 侧尤其只能出现 Key 名称，绝不能出现明文 Key。
    assertThat(principal.describe()).isEqualTo(Principal.Kind.API_KEY + ":" + KEY_NAME);
    assertThat(principal.describe()).doesNotContain(KEY_DISPLAY_NAME);
  }

  @Test
  @DisplayName("orgIds_默认空_与 flag 关语义对齐")
  void orgIdsAreAbsentByDefault() {
    Principal principal =
        Principal.user(USER_ID, USER_DISPLAY_NAME, Set.of(Role.EDITOR), Set.of("ops"));
    assertThat(principal.orgIds()).isEmpty();
    assertThat(principal.hasOrg("acme")).isFalse();
  }

  @Test
  @DisplayName("orgIds_可声明且不可变")
  void orgIdsCanBeDeclaredAndAreImmutable() {
    Principal principal =
        Principal.user(
            USER_ID, USER_DISPLAY_NAME, Set.of(Role.EDITOR), Set.of("ops"), Set.of("acme"));
    assertThat(principal.orgIds()).containsExactly("acme");
    assertThat(principal.hasOrg("acme")).isTrue();
    assertThatThrownBy(() -> principal.orgIds().add("beta"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
