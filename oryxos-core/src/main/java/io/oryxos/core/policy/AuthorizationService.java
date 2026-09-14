package io.oryxos.core.policy;

import io.oryxos.core.auth.Principal;

/**
 * 授权决策契约（039-identity-authorization）：全系统唯一的「这个人能不能做这件事」裁决点。
 *
 * <p>本接口要解决的正是 #462 的验收核心——「API、管理台、运行时共用同一授权决策」。今天的现实是 {@code BasicAuthFilter}（拦 {@code
 * /admin/*}）与 {@code ApiKeyAuthFilter}（拦 {@code /api/v1|v2/*}、 {@code /actuator/*}）各自只回答「认证通过了吗」，两条
 * URL 模式互不重叠，运行时链路连主体都没有， 因此那个「共用的决策点」根本不存在。本接口就是把它显式建出来。
 *
 * <p>契约放 {@code oryxos-core} 是依赖倒置：消费方（Web 侧的 Filter、Controller，以及将来运行时 的工具执行链路）只认本接口；实现落 {@code
 * oryxos-storage}，与既有 {@link ToolPolicyService} 的落地方式一致（接口在 core、SQLite 实现在 storage）。
 *
 * <p>三条硬约束（违反即视为设计错误）：
 *
 * <ol>
 *   <li><b>默认关零行为变化</b>：未启用时统一注入 {@link #ALLOW_ALL}，行为与无授权层时逐字节一致 —— 这与 018 的
 *       「认证开关默认关、回归零破坏」同一纪律，是宪法级的，不是可选项。
 *   <li><b>拒绝必须可解释</b>：{@link Decision#reason()} 会进审计，必须是能被人读懂的一句话，不是错误码。
 *   <li><b>不自带缓存</b>：会话有效期默认 12 小时的既有取舍意味着角色撤销若被缓存会延迟生效；本项目要求每请求 解析一次主体，因此实现不得在决策点内部缓存主体角色。
 * </ol>
 *
 * <p>与 {@link ToolPolicyService} 正交：策略管「Agent 能不能用这个工具」，本接口管「谁能不能做这件事」， 两道独立叠加、互不豁免。
 */
public interface AuthorizationService {

  /**
   * 全允空实现：未启用授权时统一注入它，行为与无授权层时代完全一致（零破坏锚点）。
   *
   * <p>注意它的返回值是 {@link Decision#ALLOWED}（{@code reason} 为 {@code null}），不是「没有决策」—— 调用方永远不需要判空。
   */
  AuthorizationService ALLOW_ALL = (principal, action, resource) -> Decision.ALLOWED;

  /**
   * 单次裁决。
   *
   * @param principal 请求主体；{@code null} 由实现归一为匿名主体（防御式，不抛异常，避免上游遗漏赋值打挂请求）
   * @param action 想做的动作
   * @param resource 动作作用的资源；整体性动作可传 {@link ResourceRef#workspace()}
   * @return 允许或拒绝，拒绝时带可读理由
   */
  Decision decide(Principal principal, Action action, ResourceRef resource);

  /** 便捷判断：仅当允许时返回 {@code true}（调用方无需读 reason 时用）。 */
  default boolean isAllowed(Principal principal, Action action, ResourceRef resource) {
    return decide(principal, action, resource).allowed();
  }

  /** 裁决结果：{@code allowed=false} 时 {@code reason} 为可进审计的人话描述。 */
  record Decision(boolean allowed, String reason) {

    /** 允许（{@code reason} 为 {@code null}）。 */
    public static final Decision ALLOWED = new Decision(true, null);

    /** 构造拒绝结果：理由不可为空——空理由会让审计失去意义。 */
    public static Decision denied(String reason) {
      return new Decision(false, reason == null || reason.isBlank() ? "被授权策略拒绝" : reason);
    }
  }
}
