package io.oryxos.web.security;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.policy.Action;
import io.oryxos.core.policy.AuthorizationService;
import io.oryxos.core.policy.ResourceRef;
import io.oryxos.web.config.WebRbacProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RBAC 强制点（039-identity-authorization）：把认证成功的请求升级为「认证 + 授权裁决」。
 *
 * <p>为什么单独成类而不是把逻辑塞进 Filter：授权必须只有一处实现。若每个入口各自实现一遍裁决与拒绝语义，
 * 就会出现「一扇门收紧、另一扇忘了收」的偏差——而这类偏差在默认关的部署上完全观察不到。
 *
 * <p><b>本刀的作用域（务必读清，别把注释当承诺）</b>：只接在 {@code ApiKeyAuthFilter}（拦 {@code /api/v1|v2/*}、{@code
 * /actuator/*}）上。 {@code BasicAuthFilter}（拦 {@code /admin/*}）<b>本刀未接线</b> ——{@code /admin/**}
 * 的静态资源与登录页不在授权范围内；但管理台的数据面本来就走 {@code /api/v1/**}（018 的 「session
 * 即凭据」），所以管理台用户的越权数据访问仍会被本条切面裁决。要不要把 {@code /admin/**} 也纳入， 属 039 spec 的待裁决项，不在本刀自作主张。
 *
 * <p>为什么走「认证门内部」而不是新增一个 Filter：{@code ApiKeyAuthFilter} 注册的 {@code PROTECTED_URL_PATTERNS}
 * 是单个手维护数组，其自身 Javadoc 明写「新增 API 版本必须同步登记，否则该整棵子树匿名 可达」。授权若靠「再加一个 Filter / 再加一组
 * pattern」实现，等于把这个坑复制一份。
 *
 * <p>短路语义（决定默认部署不受影响的关键）：
 *
 * <ul>
 *   <li>{@code rbac.enabled=false}：{@link #isActive()} 为 false，调用方连主体都不解析，放行且无额外开销。
 *   <li>{@code rbac.enabled=true} 但认证门本身关闭：上游 Filter 根本不会调用本类（它们在 {@code isEnabled()} 为 false
 *       时最早返回），因此单机零配置部署不会被自己的授权层锁死。<b>若将来有调用点绕开这个前提， 必须显式处理「无认证源却有主体」的情况。</b>
 *   <li>{@code rbac.enabled=true} 且有门产出主体：匿名主体按 {@code denyAnonymous} 拒绝；已认证主体要求至少 {@link
 *       Action#READ_WORKSPACE}（即 VIEWER 及以上），否则拒绝。
 * </ul>
 *
 * <p>为什么第一刀只做到这里：本切面刻意不做「路径 → 动作」的完整映射。映射表一旦拍错，正确行为会被静默拒绝， 而完整映射依赖 maintainer 对资源边界的裁决（见 issue #462
 * 的待决问题）。先让「唯一决策点」在结构上成立， 再往 {@link AuthorizationService} 里填政策——这也是 #462 验收里「共用同一授权决策」的可验证形态。
 */
public final class RbacEnforcer {

  private static final Logger LOG = LoggerFactory.getLogger(RbacEnforcer.class);

  /** 放行判定（启用时所有已认证请求的最低要求）。 */
  private static final Action BASELINE_ACTION = Action.READ_WORKSPACE;

  /** RBAC 拒绝的状态码：已认证但无权 → 403（区别于认证失败的 401）。 */
  static final int FORBIDDEN_CODE = HttpServletResponse.SC_FORBIDDEN;

  private static final String LOG_DENIED = "RBAC 拒绝：actor={} action={} resource={} reason={}";

  private final AuthorizationService authorizationService;

  private final WebRbacProperties properties;

  public RbacEnforcer(AuthorizationService authorizationService, WebRbacProperties properties) {
    this.authorizationService = authorizationService;
    this.properties = properties;
  }

  /**
   * 切面是否处于启用状态。
   *
   * <p>调用方靠它避免「为了填主体而做无谓工作」：解析 API Key 名称要多打一次库，未启用授权时那次读取纯属浪费， 而且会让「默认关 =
   * 零行为变化」的承诺出现一道可观测的裂缝。因此本方法是「是否需要主体」的唯一判据。
   *
   * @return {@code true} 表示需要把请求主体解析出来并交给 {@link #authorize} 裁决
   */
  public boolean isActive() {
    return properties != null && properties.isEnabled();
  }

  /**
   * 认证成功后的授权裁决。
   *
   * @return {@code true} 表示已放行，调用方应继续 filterChain；{@code false} 表示已写出拒绝响应，调用方必须 立即返回
   */
  public boolean authorize(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (!isActive()) {
      return true;
    }
    Principal principal = PrincipalHolder.get(request);
    if (principal.isAnonymous()) {
      if (properties.isDenyAnonymous()) {
        deny(response, principal, "匿名主体在授权启用时不得访问");
        return false;
      }
      return true;
    }
    AuthorizationService.Decision decision =
        authorizationService.decide(principal, BASELINE_ACTION, ResourceRef.workspace());
    if (decision.allowed()) {
      return true;
    }
    deny(response, principal, decision.reason());
    return false;
  }

  /** 统一拒绝：写 403 + 留审计日志（拒绝必须可解释，理由来自决策点）。 */
  private void deny(HttpServletResponse response, Principal principal, String reason)
      throws IOException {
    LOG.warn(
        LOG_DENIED,
        principal.describe(),
        BASELINE_ACTION,
        ResourceRef.workspace().describe(),
        reason);
    response.setStatus(FORBIDDEN_CODE);
  }
}
