package io.oryxos.core.auth;

/**
 * 运行时请求主体的线程上下文（039 / #462 / #503）：web 入口在调用 {@code AgentService} 前 {@link #set}，出口 {@link #clear}。
 *
 * <p>与 {@link io.oryxos.core.agent.ProfileContext} 同纪律——虚拟线程每请求独立；复用平台线程时 finally 必达，否则会串主体。
 * 本载体只保存主体，不替代 {@link io.oryxos.core.policy.AuthorizationService}；{@code ToolExecutor} 在有上下文时
 * {@code decide(RUN_AGENT)}（#527）。
 */
public final class PrincipalContext {

  private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

  private PrincipalContext() {}

  public static void set(Principal principal) {
    CURRENT.set(principal);
  }

  /** 未设置时返回 null（调用方自行决定是否回落匿名）。 */
  public static Principal current() {
    return CURRENT.get();
  }

  /** 用 remove() 而非 set(null)：防 ThreadLocal 泄漏。 */
  public static void clear() {
    CURRENT.remove();
  }
}
