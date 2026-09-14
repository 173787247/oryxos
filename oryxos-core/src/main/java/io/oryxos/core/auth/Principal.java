package io.oryxos.core.auth;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 统一请求主体（039-identity-authorization）：把「认证通过了吗」升级成「谁、以什么身份在请求」。
 *
 * <p>现状问题：仓库里两扇自研 Filter 各自只回答「认证通过了吗」——{@code BasicAuthFilter} 把管理台 session 塌缩成 {@code username}
 * 字符串，{@code ApiKeyAuthFilter} 只把 API Key 校验成布尔值， 运行时链路（ReAct 循环 / ToolExecutor）里根本没有任何「代表谁执行」的载体。
 * 于是「API、管理台、运行时共用同一授权决策」这条 #462 验收标准缺少最基础的前提：三条路径连主体都不共享。
 *
 * <p>本类型就是这个前提：三种来源表达成同一等主体，后续所有授权裁决都只认它。
 *
 * <ul>
 *   <li>{@link Kind#USER}：管理台 Basic Auth / 登录 session 对应的账号，可带角色。
 *   <li>{@link Kind#API_KEY}：某个调用方持有的 API Key，可带该 Key 被授予的角色。
 *   <li>{@link Kind#ANONYMOUS}：未认证请求——<b>仍然是一个主体</b>，不是 null。拒绝对匿名主体同样要给出
 *       可读理由并留痕，这正是「拒绝行为明确且有审计」的落点。
 * </ul>
 *
 * <p>为什么用不可变 record：主体在一条请求的处理链路上会被 Filter、Controller、授权决策点多方读取，
 * 可变对象会带来「谁在什么时机改了角色」的排查成本。角色集合在构造时去重并冻结。
 *
 * <p>线程/请求边界：主体<b>不放在全局静态变量</b>里。servlet 容器会复用工作线程，Web 侧务必用请求属性 携带（见 oryxos-web 的请求属性承载），工具执行链路继续沿用
 * {@link io.oryxos.core.agent.ToolExecutionContext} 既有的 ThreadLocal 纪律。
 *
 * @param kind 主体来源类别，{@code null} 视为 {@link Kind#ANONYMOUS}（防御式归一，不抛异常——避免因上游 遗漏赋值把整条请求打挂）
 * @param id 主体标识：USER 为用户名，API_KEY 为 Key 名称，ANONYMOUS 为固定占位
 * @param displayName 展示名，仅用于审计与界面；不参与任何裁决
 * @param roles 该主体持有的角色集合；构造时去重并冻结为不可变集合
 */
public record Principal(Kind kind, String id, String displayName, Set<Role> roles) {

  /** 匿名主体的固定标识（审计里统一出现这一个值，便于聚合「未认证访问」）。 */
  public static final String ANONYMOUS_ID = "anonymous";

  /** API Key 主体在拿不到 Key 名称时的占位标识（宁可标识不精确，也不让已通过认证的请求失败）。 */
  public static final String API_KEY_FALLBACK_ID = "api-key:unknown";

  /** 主体来源类别。 */
  public enum Kind {
    /** 管理台账号（Basic Auth 或登录 session）。 */
    USER,
    /** API Key 调用方。 */
    API_KEY,
    /** 未认证请求——仍是一等主体，不是 null。 */
    ANONYMOUS
  }

  /** 紧凑构造器：归一 null 并冻结角色集合，保证 record 真正不可变。 */
  public Principal {
    kind = kind == null ? Kind.ANONYMOUS : kind;
    roles =
        roles == null || roles.isEmpty()
            ? Set.of()
            : Collections.unmodifiableSet(EnumSet.copyOf(roles));
  }

  /** 管理台账号主体。 */
  public static Principal user(String id, String displayName, Set<Role> roles) {
    return new Principal(Kind.USER, id, displayName, roles);
  }

  /** API Key 调用方主体（无角色 = 该 Key 未被授予任何角色，裁决时一律拒绝）。 */
  public static Principal apiKey(String keyName, String displayName) {
    return new Principal(Kind.API_KEY, keyName, displayName, Set.of());
  }

  /** API Key 调用方主体：显式授予角色（权限来源是 Key 上的授权，不是操作者身份）。 */
  public static Principal apiKey(String keyName, String displayName, Set<Role> roles) {
    return new Principal(Kind.API_KEY, keyName, displayName, roles);
  }

  /** 未认证主体（仅此一个语义，不携带任何角色）。 */
  public static Principal anonymous() {
    return new Principal(Kind.ANONYMOUS, ANONYMOUS_ID, ANONYMOUS_ID, Set.of());
  }

  /**
   * 角色视图：每次返回防御性副本，避免 SpotBugs EI_EXPOSE_REP（即便字段已是不可变包装，accessor 直接返回字段引用仍会被判定）。
   */
  @Override
  public Set<Role> roles() {
    return roles.isEmpty() ? Set.of() : Set.copyOf(roles);
  }

  /** 是否未认证主体。 */
  public boolean isAnonymous() {
    return kind == Kind.ANONYMOUS;
  }

  /** 是否持有指定角色。 */
  public boolean hasRole(Role role) {
    return role != null && roles.contains(role);
  }

  /**
   * 主体是否可用于授权裁决：匿名主体与「一个角色都没有」的主体都不行。
   *
   * <p>把这条判断收在这里，是为了让「空角色 = 拒绝」成为全系统一致的口径，而不是每个调用点各写一遍。
   */
  public boolean isAuthorizable() {
    return !isAnonymous() && !roles.isEmpty();
  }

  /** 审计友好的一行描述（不含任何凭证；API Key 只出现名称，绝不出现明文）。 */
  public String describe() {
    return kind + ":" + Objects.toString(id, "?");
  }
}
