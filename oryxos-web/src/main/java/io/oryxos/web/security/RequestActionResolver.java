package io.oryxos.web.security;

import io.oryxos.core.policy.Action;
import io.oryxos.core.policy.ResourceRef;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;

/**
 * 路径 → 动作映射（039 第二刀）：契约 §4.2 的唯一登记处。
 *
 * <p>返回语义：
 *
 * <ul>
 *   <li>{@link Resolution#skip()} 工厂 / {@link Resolution#isSkip()} —— 不裁决（认证面 / 存活面 / 渠道入站 / Alipay
 *       兼容口）
 *   <li>{@link Resolution#of(Action, ResourceRef)} —— 命中登记表
 *   <li>{@code null} —— 未登记：调用方 fail-closed 拒绝
 * </ul>
 *
 * <p>相对契约初稿的补登记（路由盘点）：{@code /api/v2/schedules/**} 与 v1 调度同档；{@code POST /api/v1} （Alipay
 * 截断网关）按入站渠道同口径只认证不裁决——否则 RBAC 一开即打挂支付宝回调。
 */
public final class RequestActionResolver {

  private RequestActionResolver() {}

  /**
   * @return 映射结果；{@code null} 表示未登记（应拒绝）
   */
  public static Resolution resolve(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase();
    String path = normalize(request.getRequestURI());
    return resolve(method, path);
  }

  /** 纯函数入口（单测友好）。 */
  public static Resolution resolve(String method, String path) {
    String m = method == null ? "" : method.toUpperCase();
    String p = normalize(path);
    if (HttpMethod.OPTIONS.matches(m)) {
      return Resolution.skip();
    }
    if (isExact(p, "/api/v1/health")
        || isUnder(p, "/api/v1/auth")
        || isExact(p, "/actuator/health")
        || isUnder(p, "/actuator/health")) {
      return Resolution.skip();
    }
    // 支付宝截断网关：与 channels/inbound 同属外部平台回调
    if ((isExact(p, "/api/v1") || isExact(p, "/api/v1/")) && HttpMethod.POST.matches(m)) {
      return Resolution.skip();
    }
    if (isUnder(p, "/api/v1/channels/inbound")) {
      return Resolution.skip();
    }
    if (isUnder(p, "/actuator")) {
      return Resolution.of(Action.READ_WORKSPACE, ResourceRef.workspace());
    }
    if (matchesInvoke(p) && HttpMethod.POST.matches(m)) {
      return Resolution.of(Action.RUN_AGENT, ResourceRef.agent(segmentAfter(p, "/api/v1/agents/")));
    }
    if (isUnder(p, "/api/v1/sessions") || isUnder(p, "/api/v1/runs")) {
      return Resolution.of(Action.MANAGE_SESSIONS, ResourceRef.session(null));
    }
    if (isUnder(p, "/api/v1/agents")) {
      return readOrManage(
          m, Action.MANAGE_AGENTS, ResourceRef.agent(segmentAfter(p, "/api/v1/agents/")));
    }
    if (isUnder(p, "/api/v1/knowledge")) {
      return readOrManage(
          m, Action.MANAGE_KNOWLEDGE, ResourceRef.knowledge(segmentAfter(p, "/api/v1/knowledge/")));
    }
    if (isUnder(p, "/api/v1/skills")) {
      return readOrManage(
          m, Action.MANAGE_SKILLS, ResourceRef.skill(segmentAfter(p, "/api/v1/skills/")));
    }
    if (isUnder(p, "/api/v1/personas") || isUnder(p, "/api/v1/schedules")) {
      return readOrManage(m, Action.MANAGE_AGENTS, ResourceRef.agent(null));
    }
    // v2 调度：与 v1 schedules 同档（盘点缺口补登记）
    if (isUnder(p, "/api/v2/schedules") || matchesV2AgentScheduleRun(p)) {
      return readOrManage(m, Action.MANAGE_AGENTS, ResourceRef.agent(null));
    }
    if (isUnder(p, "/api/v1/channels")
        || isUnder(p, "/api/v1/notify-channels")
        || isUnder(p, "/api/v1/mcp-servers")) {
      return Resolution.of(Action.MANAGE_CHANNELS, ResourceRef.channel(null));
    }
    if (isUnder(p, "/api/v1/tool-policy") || isUnder(p, "/api/v1/sandbox")) {
      return Resolution.of(Action.MANAGE_POLICIES, ResourceRef.policy());
    }
    if (isUnder(p, "/api/v1/audit")) {
      return Resolution.of(Action.READ_AUDIT, ResourceRef.audit());
    }
    if (isUnder(p, "/api/v1/workspace")) {
      return readOrManage(m, Action.MANAGE_WORKSPACE, ResourceRef.workspace());
    }
    if (isUnder(p, "/api/v1/providers") || isUnder(p, "/api/v1/pricing")) {
      return readOrManage(m, Action.MANAGE_WORKSPACE, ResourceRef.workspace());
    }
    if (isExact(p, "/api/v1/info")
        || isUnder(p, "/api/v1/profiles")
        || isUnder(p, "/api/v1/tools")
        || isUnder(p, "/api/v1/instances")) {
      if (isRead(m)) {
        return Resolution.of(Action.READ_WORKSPACE, ResourceRef.workspace());
      }
      return null;
    }
    // 未登记：fail-closed
    return null;
  }

  private static Resolution readOrManage(String method, Action manage, ResourceRef resource) {
    if (isRead(method)) {
      return Resolution.of(Action.READ_WORKSPACE, ResourceRef.workspace());
    }
    return Resolution.of(manage, resource);
  }

  private static boolean isRead(String method) {
    return HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method);
  }

  private static boolean matchesInvoke(String path) {
    // /api/v1/agents/{name}/invoke (prefix without trailing slash for isUnder)
    if (!isUnder(path, "/api/v1/agents")) {
      return false;
    }
    return path.endsWith("/invoke");
  }

  private static boolean matchesV2AgentScheduleRun(String path) {
    // /api/v2/agents/{profile}/schedules/{key}/run
    return path.startsWith("/api/v2/agents/")
        && path.contains("/schedules/")
        && path.endsWith("/run");
  }

  private static String segmentAfter(String path, String prefix) {
    if (!path.startsWith(prefix) || path.length() <= prefix.length()) {
      return null;
    }
    String rest = path.substring(prefix.length());
    int slash = rest.indexOf('/');
    String seg = slash < 0 ? rest : rest.substring(0, slash);
    return seg.isBlank() ? null : seg;
  }

  private static String normalize(String path) {
    if (path == null || path.isBlank()) {
      return "";
    }
    // 去掉查询串（若调用方误传）
    int q = path.indexOf('?');
    String p = q < 0 ? path : path.substring(0, q);
    if (p.length() > 1 && p.endsWith("/")) {
      // 保留 "/api/v1/" 这种兼容根；其它尾斜杠去掉以便前缀匹配
      if (!isExact(p, "/api/v1/") && !isExact(p, "/api/v1")) {
        return p.substring(0, p.length() - 1);
      }
    }
    return p;
  }

  private static boolean isExact(String path, String expected) {
    return expected.equals(path);
  }

  private static boolean isUnder(String path, String prefix) {
    return path.equals(prefix) || path.startsWith(prefix + "/");
  }

  /** 一次映射结果。 */
  public static final class Resolution {
    private final boolean skipped;
    private final Action action;
    private final ResourceRef resource;

    private Resolution(boolean skipped, Action action, ResourceRef resource) {
      this.skipped = skipped;
      this.action = action;
      this.resource = resource;
    }

    public static Resolution skip() {
      return new Resolution(true, null, null);
    }

    public static Resolution of(Action action, ResourceRef resource) {
      return new Resolution(false, action, resource);
    }

    /**
     * @return {@code true} if this resolution skips authorization.
     */
    public boolean isSkip() {
      return skipped;
    }

    public Action action() {
      return action;
    }

    public ResourceRef resource() {
      return resource;
    }
  }
}
