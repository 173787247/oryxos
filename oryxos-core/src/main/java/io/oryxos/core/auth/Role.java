package io.oryxos.core.auth;

/**
 * 主体角色（039-identity-authorization）：企业控制面最小角色矩阵的三档。
 *
 * <p>为什么只有三档：企业授权模型最容易失控的地方是角色爆炸——每多一档，权限矩阵的组合数翻倍， 评审和维护成本指数上升。039 先用三档覆盖「只读 / 能干活的 /
 * 管人和管策略的」这条最小可用分界， 把资源级的细粒度 收在 {@link io.oryxos.core.policy.Action} 与 {@link
 * io.oryxos.core.policy.ResourceRef} 上， 而不是靠增加角色来解决。后续按真实企业需求再扩，扩之前先改 spec。
 *
 * <p>三档语义（契约见 specs/039-identity-authorization/contracts/）：
 *
 * <ul>
 *   <li>{@link #VIEWER}：只读——可看工作区与审计，不能跑会话、不能改任何资产。
 *   <li>{@link #EDITOR}：在 VIEWER 之上可执行与编辑——跑 Agent、管 Agent/知识库/Skill、管自己的会话。
 *   <li>{@link #ADMIN}：在 EDITOR 之上管边界本身——成员、渠道、策略、工作区设置。
 * </ul>
 *
 * <p>注意：角色回答的是「这个人是什么档」，不回答「他能碰哪个具体资源」。具体资源的裁决 一律经 {@link
 * io.oryxos.core.policy.AuthorizationService}，禁止在 Controller / Filter 里直接比对角色。
 */
public enum Role {

  /** 只读：可看工作区与审计。 */
  VIEWER,

  /** 可执行与编辑：跑 Agent、管 Agent / 知识库 / Skill、管自己的会话。 */
  EDITOR,

  /** 管边界本身：成员、渠道、策略、工作区设置。 */
  ADMIN
}
