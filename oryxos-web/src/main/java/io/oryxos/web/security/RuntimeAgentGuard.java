package io.oryxos.web.security;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.auth.PrincipalContext;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 运行时开跑 Agent 前的统一门禁（#503 / #462）：先 {@link AssetBindGuard#requireAgentRun}（同一
 * AuthorizationService），再把 {@link PrincipalHolder} 主体放入 {@link PrincipalContext} 供编排线程读取。
 *
 * <p>异步任务在提交前完成 decide；lambda 内重新 set 已捕获的 Principal（request 属性跨不了线程）。
 */
public final class RuntimeAgentGuard {

  private final AssetBindGuard assetBindGuard;

  public RuntimeAgentGuard(AssetBindGuard assetBindGuard) {
    this.assetBindGuard = assetBindGuard == null ? new AssetBindGuard(null) : assetBindGuard;
  }

  /** 同步路径：校验 + 置 ThreadLocal；调用方必须在 finally 里 {@link #clear()}。 */
  public void bindForRun(HttpServletRequest request, String agentName) {
    assetBindGuard.requireAgentRun(request, agentName);
    PrincipalContext.set(PrincipalHolder.get(request));
  }

  /** 异步提交前：校验并返回要带进后台线程的主体（已通过 decide）。 */
  public Principal authorizeAndCapture(HttpServletRequest request, String agentName) {
    assetBindGuard.requireAgentRun(request, agentName);
    return PrincipalHolder.get(request);
  }

  public static void install(Principal principal) {
    PrincipalContext.set(principal);
  }

  public static void clear() {
    PrincipalContext.clear();
  }
}
