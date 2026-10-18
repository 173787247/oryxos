package io.oryxos.web.security;

import io.oryxos.storage.WebUserService;
import io.oryxos.web.config.WebApiKeyProperties;
import io.oryxos.web.config.WebAuthProperties;
import io.oryxos.web.config.WebRbacProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * 启动校验（039-identity-authorization，FR-012）：{@code rbac.enabled=true} 时 fail-fast， 避免授权静默失效或治理面锁死。
 *
 * <p>为什么必须 fail-fast：
 *
 * <ul>
 *   <li>未开 {@code apikey} 时没有任何门产出主体，RBAC 会被短路为「放行但无主体」——授权形同虚设；
 *   <li>无 ADMIN 账号且 {@code default-user-roles} 已收紧为空时，无人能管理成员/策略——治理面永久锁死。
 * </ul>
 *
 * <p>{@code auth.enabled=false} 只 WARN（管理台不可用是合法纯 API 部署，镜像 018 {@code ApiKeyStartupCheck}）。用
 * {@link SmartInitializingSingleton}：校验在全部单例初始化完成后、Web Server 开始监听端口之前执行（与 {@code
 * ProviderStartupCheck}/{@code ClusterStartupCheck} 同款）。{@code ApplicationRunner} 太晚——Tomcat 在
 * {@code finishRefresh()} 里已经打开端口，fail-open 窗口会被 K8s 探针/滚动发布调度打到。
 *
 * <p>{@link ConditionalOnWebApplication} 限定只 SERVLET 模式装配，CLI 管理命令（{@code
 * WebApplicationType.NONE}）不受影响。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RbacStartupCheck implements SmartInitializingSingleton {

  private static final Logger LOG = LoggerFactory.getLogger(RbacStartupCheck.class);

  static final String MSG_APIKEY_REQUIRED =
      "RBAC enabled (oryxos.web.rbac.enabled=true) but API key auth is off"
          + " (oryxos.web.apikey.enabled=false). No gate produces a principal;"
          + " authorization would silently fail open. Enable oryxos.web.apikey.enabled"
          + " before starting serve (FR-012 fail-closed).";

  static final String MSG_ADMIN_REQUIRED =
      "RBAC enabled (oryxos.web.rbac.enabled=true) but no ADMIN account found."
          + " Governance would be locked out. Run 'oryxos user role <name> ADMIN'"
          + " before starting serve.";

  static final String MSG_AUTH_OFF_WARN =
      "RBAC enabled but web auth disabled (oryxos.web.auth.enabled=false)."
          + " Admin console data pages will be unusable without session login."
          + " Enable oryxos.web.auth.enabled or use REST API only.";

  private final WebRbacProperties rbacProperties;
  private final WebApiKeyProperties apiKeyProperties;
  private final WebAuthProperties authProperties;
  private final WebUserService userService;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = {"EI_EXPOSE_REP2"},
      justification =
          "rbac/apikey/auth properties 与 userService 均为 Spring 注入的共享单例，构造注入存同一引用正是意图"
              + "（镜像 AuthStartupCheck 的 SuppressFBWarnings 模式）。")
  public RbacStartupCheck(
      WebRbacProperties rbacProperties,
      WebApiKeyProperties apiKeyProperties,
      WebAuthProperties authProperties,
      WebUserService userService) {
    this.rbacProperties = rbacProperties;
    this.apiKeyProperties = apiKeyProperties;
    this.authProperties = authProperties;
    this.userService = userService;
  }

  @Override
  public void afterSingletonsInstantiated() {
    check();
  }

  /** 校验主体（包级可见：单测直接调；相位由 {@link #afterSingletonsInstantiated()} 保证在端口打开前）。 */
  void check() {
    if (!rbacProperties.isEnabled()) {
      LOG.debug("RBAC disabled (oryxos.web.rbac.enabled=false), startup check skipped");
      return;
    }
    if (!apiKeyProperties.isEnabled()) {
      LOG.error(MSG_APIKEY_REQUIRED);
      throw new IllegalStateException(MSG_APIKEY_REQUIRED);
    }
    if (!userService.hasAdminAccount()) {
      LOG.error(MSG_ADMIN_REQUIRED);
      throw new IllegalStateException(MSG_ADMIN_REQUIRED);
    }
    if (!authProperties.isEnabled()) {
      LOG.warn(MSG_AUTH_OFF_WARN);
    }
    LOG.debug(
        "RBAC startup check passed (enabled={}, apikey={}, auth={}, admin present)",
        rbacProperties.isEnabled(),
        apiKeyProperties.isEnabled(),
        authProperties.isEnabled());
  }
}
