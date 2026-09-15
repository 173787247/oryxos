package io.oryxos.core.policy;

import java.util.Objects;

/**
 * 授权资源引用（039-identity-authorization）：动作作用在「什么」上的最小描述。
 *
 * <p>本类型刻意只承载「类型 + 标识」两个字段，不持有任何真实资源对象。原因有二：
 *
 * <ul>
 *   <li>授权决策会被 API、管理台、运行时三条路径调用，若要求传入真实实体，运行时（ReAct 循环里没有 Controller
 *       上下文）就无法参与——那正是今天「三条路径无法共用决策」的成因之一。
 *   <li>不可变引用可以被安全地放进拒绝审计（{@code type + id} 足够定位，且不含业务数据，天然符合脱敏口径）。
 * </ul>
 *
 * <p>类型常量集中在此，避免各调用点手写字符串导致拼写漂移而被静默放行。
 */
public record ResourceRef(String type, String id) {

  /** 工作区 / 边界整体。 */
  public static final String TYPE_WORKSPACE = "workspace";

  /** Agent。 */
  public static final String TYPE_AGENT = "agent";

  /** 知识库。 */
  public static final String TYPE_KNOWLEDGE = "knowledge";

  /** Skill。 */
  public static final String TYPE_SKILL = "skill";

  /** 渠道。 */
  public static final String TYPE_CHANNEL = "channel";

  /** 会话。 */
  public static final String TYPE_SESSION = "session";

  /** 审计数据。 */
  public static final String TYPE_AUDIT = "audit";

  /** 治理策略。 */
  public static final String TYPE_POLICY = "policy";

  /** 成员。 */
  public static final String TYPE_MEMBER = "member";

  /** 边界整体（id 为空表示「整个边界」，用于 READ_WORKSPACE / MANAGE_WORKSPACE 这类整体动作）。 */
  public static ResourceRef workspace() {
    return new ResourceRef(TYPE_WORKSPACE, null);
  }

  /** 指定 Agent。 */
  public static ResourceRef agent(String name) {
    return new ResourceRef(TYPE_AGENT, name);
  }

  /** 指定知识库。 */
  public static ResourceRef knowledge(String nameOrId) {
    return new ResourceRef(TYPE_KNOWLEDGE, nameOrId);
  }

  /** 指定 Skill。 */
  public static ResourceRef skill(String name) {
    return new ResourceRef(TYPE_SKILL, name);
  }

  /** 指定渠道。 */
  public static ResourceRef channel(String name) {
    return new ResourceRef(TYPE_CHANNEL, name);
  }

  /** 指定会话。 */
  public static ResourceRef session(String sessionId) {
    return new ResourceRef(TYPE_SESSION, sessionId);
  }

  /** 审计数据（无单条标识，整体面）。 */
  public static ResourceRef audit() {
    return new ResourceRef(TYPE_AUDIT, null);
  }

  /** 治理策略（无单条标识，整体面）。 */
  public static ResourceRef policy() {
    return new ResourceRef(TYPE_POLICY, null);
  }

  /** 指定成员。 */
  public static ResourceRef member(String memberId) {
    return new ResourceRef(TYPE_MEMBER, memberId);
  }

  /** 审计友好描述：{@code type:id}，id 为空时只出类型。 */
  public String describe() {
    return Objects.isNull(id) || id.isBlank() ? type : type + ":" + id;
  }
}
