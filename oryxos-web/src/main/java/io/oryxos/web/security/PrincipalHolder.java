package io.oryxos.web.security;

import io.oryxos.core.auth.Principal;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 请求级主体承载（039-identity-authorization）：让「谁在请求」从 Filter 传到 Controller 与授权决策点。
 *
 * <p>为什么用请求属性而不是 ThreadLocal：servlet 容器会复用工作线程，ThreadLocal 一旦在某条提前返回的 分支上漏了 {@code
 * remove()}，下一个请求就会继承上一个请求的主体——在授权场景里这是**越权**，不是脏读。 请求属性随请求生命周期天然回收，不存在这条泄漏路径，因此 Web
 * 侧一律用它。（工具执行链路仍沿用 {@link io.oryxos.core.agent.ToolExecutionContext} 既有的 ThreadLocal
 * 纪律，那条链路是同步阻塞、start/finally 清除的）
 *
 * <p>为什么不用 {@code request.setAttribute} 裸写而收一层：属性名必须唯一且集中，否则两个 Filter 各自写一个 名字、Controller
 * 读第三个名字时，会出现「主体看起来为空、授权静默放行」——这类缺陷在测试里很难被发现， 因为默认关时它完全观察不到。
 */
public final class PrincipalHolder {

  /** 请求属性名（包内唯一来源）。 */
  static final String ATTRIBUTE = "io.oryxos.web.principal";

  private PrincipalHolder() {}

  /** 置入主体（认证成功的 Filter 调用）。 */
  public static void set(HttpServletRequest request, Principal principal) {
    if (principal != null) {
      request.setAttribute(ATTRIBUTE, principal);
    }
  }

  /** 读取主体；未认证返回 {@link Principal#anonymous()}，永不返回 {@code null}。 */
  public static Principal get(HttpServletRequest request) {
    Object value = request.getAttribute(ATTRIBUTE);
    return value instanceof Principal principal ? principal : Principal.anonymous();
  }

  /** 是否已有已认证主体（供需要区分「匿名」与「尚未认证」的调用点使用）。 */
  public static boolean isAuthenticated(HttpServletRequest request) {
    return request.getAttribute(ATTRIBUTE) instanceof Principal principal
        && !principal.isAnonymous();
  }
}
