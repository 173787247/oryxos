package io.oryxos.web.security;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.policy.Action;
import io.oryxos.core.policy.AuthorizationService;
import io.oryxos.core.policy.ResourceRef;
import io.oryxos.storage.AuthzEventRecorder;
import io.oryxos.web.config.WebRbacProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RBAC 强制点（039-identity-authorization）：把认证成功的请求升级为「认证 + 授权裁决」。
 *
 * <p>第二刀：按 {@link RequestActionResolver} 做路径→动作映射；未登记路径 fail-closed；豁免路径不裁决。 拒绝时写 403 + 结构化
 * WARN，并尽量落 {@code authz_events}（写失败不改变裁决）。
 */
public final class RbacEnforcer {

  private static final Logger LOG = LoggerFactory.getLogger(RbacEnforcer.class);

  /** RBAC 拒绝的状态码：已认证但无权 → 403（区别于认证失败的 401）。 */
  static final int FORBIDDEN_CODE = HttpServletResponse.SC_FORBIDDEN;

  private static final String LOG_DENIED = "RBAC 拒绝：actor={} action={} resource={} reason={}";

  private static final String REASON_UNMAPPED = "未登记的受保护路径（授权 fail-closed）";

  private final AuthorizationService authorizationService;

  private final WebRbacProperties properties;

  private final AuthzEventRecorder authzEventRecorder;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "authorizationService/properties/recorder 均为 Spring 注入的共享单例，构造注入存同一引用正是意图"
              + "（镜像 AuthStartupCheck / ApiKeyAuthFilter 的 SuppressFBWarnings 模式）。")
  public RbacEnforcer(
      AuthorizationService authorizationService,
      WebRbacProperties properties,
      AuthzEventRecorder authzEventRecorder) {
    this.authorizationService = authorizationService;
    this.properties = properties;
    this.authzEventRecorder = authzEventRecorder;
  }

  /** 兼容单测：无审计落库时 recorder 可为 null。 */
  public RbacEnforcer(AuthorizationService authorizationService, WebRbacProperties properties) {
    this(authorizationService, properties, null);
  }

  /**
   * 切面是否处于启用状态。
   *
   * @return {@code true} 表示需要把请求主体解析出来并交给 {@link #authorize} 裁决
   */
  public boolean isActive() {
    return properties != null && properties.isEnabled();
  }

  /**
   * 认证成功后的授权裁决。
   *
   * @return {@code true} 表示已放行；{@code false} 表示已写出拒绝响应
   */
  public boolean authorize(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (!isActive()) {
      return true;
    }
    Principal principal = PrincipalHolder.get(request);
    RequestActionResolver.Resolution resolution = RequestActionResolver.resolve(request);
    if (resolution != null && resolution.isSkip()) {
      return true;
    }
    if (principal.isAnonymous()) {
      if (properties.isDenyAnonymous()) {
        Action action = resolution == null ? Action.READ_WORKSPACE : resolution.action();
        ResourceRef resource = resolution == null ? ResourceRef.workspace() : resolution.resource();
        deny(request, response, principal, action, resource, "匿名主体在授权启用时不得访问");
        return false;
      }
      return true;
    }
    if (resolution == null) {
      deny(
          request,
          response,
          principal,
          Action.READ_WORKSPACE,
          ResourceRef.workspace(),
          REASON_UNMAPPED);
      return false;
    }
    AuthorizationService.Decision decision =
        authorizationService.decide(principal, resolution.action(), resolution.resource());
    if (decision.allowed()) {
      return true;
    }
    deny(
        request,
        response,
        principal,
        resolution.action(),
        resolution.resource(),
        decision.reason());
    return false;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification =
          "日志字段为 Principal.describe()/Action 枚举/ResourceRef.describe()/Decision.reason；"
              + "前三者由内部类型生成，reason 来自授权决策点固定文案，均非未消毒的请求原文"
              + "（镜像 ApiKeyService 的 SuppressFBWarnings 模式）。")
  private void deny(
      HttpServletRequest request,
      HttpServletResponse response,
      Principal principal,
      Action action,
      ResourceRef resource,
      String reason)
      throws IOException {
    Action effectiveAction = action == null ? Action.READ_WORKSPACE : action;
    ResourceRef effectiveResource = resource == null ? ResourceRef.workspace() : resource;
    LOG.warn(
        LOG_DENIED, principal.describe(), effectiveAction, effectiveResource.describe(), reason);
    if (authzEventRecorder != null) {
      authzEventRecorder.record(
          principal,
          effectiveAction,
          effectiveResource,
          reason,
          request == null ? null : request.getMethod(),
          request == null ? null : request.getRequestURI());
    }
    response.setStatus(FORBIDDEN_CODE);
  }
}
