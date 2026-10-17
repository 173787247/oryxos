package io.oryxos.web.security;

import io.oryxos.core.policy.Action;
import io.oryxos.core.policy.ResourceRef;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
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
 *
 * <p>路由盘点的第二批：{@code /api/v1/capabilities} 与 {@code /api/v1/routing}（只读目录）、{@code
 * /api/v1/evals}（审计两表，与 /audit 同档）、{@code /api/v1/flows} 与 {@code /api/v1/team-tasks}（写方法按
 * RUN_AGENT）。这五个前缀此前从未登记，{@code oryxos.web.rbac.enabled=true} 时会被 039 SC-005 的启动检查拒绝 —— 其中 {@code
 * team-tasks} 带 {@code @ConditionalOnProperty}，只在开关打开时才注册，因此最难在人工盘点上被看见。
 */
public final class RequestActionResolver {

  private static final String PATH_SEP = "/";

  private static final String PATH_API_V1 = "/api/v1";

  private static final String PATH_API_V1_SLASH = "/api/v1/";

  private static final String PATH_API_V1_HEALTH = "/api/v1/health";

  private static final String PATH_API_V1_AUTH = "/api/v1/auth";

  private static final String PATH_API_V1_INFO = "/api/v1/info";

  private static final String PATH_ACTUATOR = "/actuator";

  private static final String PATH_ACTUATOR_HEALTH = "/actuator/health";

  private static final String PATH_API_V1_A2A = "/api/v1/a2a";

  private static final String PATH_CHANNELS_INBOUND = "/api/v1/channels/inbound";

  private static final String PATH_SESSIONS = "/api/v1/sessions";

  private static final String PATH_RUNS = "/api/v1/runs";

  private static final String PATH_AGENTS = "/api/v1/agents";

  private static final String PATH_AGENTS_PREFIX = "/api/v1/agents/";

  private static final String PATH_KNOWLEDGE = "/api/v1/knowledge";

  private static final String PATH_KNOWLEDGE_PREFIX = "/api/v1/knowledge/";

  private static final String PATH_SKILLS = "/api/v1/skills";

  private static final String PATH_SKILLS_PREFIX = "/api/v1/skills/";

  private static final String PATH_PERSONAS = "/api/v1/personas";

  private static final String PATH_SCHEDULES = "/api/v1/schedules";

  private static final String PATH_V2_SCHEDULES = "/api/v2/schedules";

  private static final String PATH_V2_AGENTS_PREFIX = "/api/v2/agents/";

  private static final String PATH_CHANNELS = "/api/v1/channels";

  private static final String PATH_NOTIFY_CHANNELS = "/api/v1/notify-channels";

  private static final String PATH_MCP_SERVERS = "/api/v1/mcp-servers";

  private static final String PATH_TOOL_POLICY = "/api/v1/tool-policy";

  private static final String PATH_SANDBOX = "/api/v1/sandbox";

  private static final String PATH_AUDIT = "/api/v1/audit";

  private static final String PATH_WORKSPACE = "/api/v1/workspace";

  private static final String PATH_PROVIDERS = "/api/v1/providers";

  private static final String PATH_PRICING = "/api/v1/pricing";

  private static final String PATH_COST = "/api/v1/cost";

  private static final String PATH_CAPABILITIES = "/api/v1/capabilities";

  private static final String PATH_FLOWS = "/api/v1/flows";

  private static final String PATH_EVALS = "/api/v1/evals";

  private static final String PATH_ROUTING = "/api/v1/routing";

  private static final String PATH_TEAM_TASKS = "/api/v1/team-tasks";

  private static final String PATH_PROFILES = "/api/v1/profiles";

  private static final String PATH_TOOLS = "/api/v1/tools";

  private static final String PATH_INSTANCES = "/api/v1/instances";

  private static final String PATH_TEAMS = "/api/v1/teams";

  private static final String PATH_ORGS = "/api/v1/orgs";

  private static final String PATH_IDENTITY_MAPPINGS = "/api/v1/identity-mappings";

  private static final String PATH_APPROVALS = "/api/v1/approvals";

  private static final String PATH_APPROVAL_CALLBACKS = "/api/v1/approvals/callbacks";

  private static final String PATH_USERS = "/api/v1/users";

  private static final String SEGMENT_TEAMS = "/teams";

  private static final String SUFFIX_INVOKE = "/invoke";

  private static final String SEGMENT_SCHEDULES = "/schedules/";

  private static final String SUFFIX_RUN = "/run";

  private static final String QUERY_MARK = "?";

  private RequestActionResolver() {}

  /**
   * @return 映射结果；{@code null} 表示未登记（应拒绝）
   */
  public static Resolution resolve(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase(Locale.ROOT);
    String path = normalize(request.getRequestURI());
    return resolve(method, path);
  }

  /** 纯函数入口（单测友好）。 */
  public static Resolution resolve(String method, String path) {
    String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
    String p = normalize(path);
    if (HttpMethod.OPTIONS.matches(m)) {
      return Resolution.skip();
    }
    if (isSkipPath(p)) {
      return Resolution.skip();
    }
    // 支付宝截断网关：与 channels/inbound 同属外部平台回调
    if (isAlipayCompatGateway(m, p)) {
      return Resolution.skip();
    }
    if (isUnder(p, PATH_CHANNELS_INBOUND) || isUnder(p, PATH_APPROVAL_CALLBACKS)) {
      return Resolution.skip();
    }
    if (isUnder(p, PATH_ACTUATOR)) {
      return Resolution.of(Action.READ_WORKSPACE, ResourceRef.workspace());
    }
    if (matchesInvoke(p) && HttpMethod.POST.matches(m)) {
      return Resolution.of(
          Action.RUN_AGENT, ResourceRef.agent(segmentAfter(p, PATH_AGENTS_PREFIX)));
    }
    // A2A JSON-RPC: a peer drives an Agent turn through message/send, the same capability as
    // POST /api/v1/agents/{name}/invoke. The Agent is named in the request body, so the resource
    // is unqualified — as it is for personas and schedules.
    if (isUnder(p, PATH_API_V1_A2A)) {
      return Resolution.of(Action.RUN_AGENT, ResourceRef.agent(null));
    }
    if (isUnder(p, PATH_SESSIONS) || isUnder(p, PATH_RUNS)) {
      return Resolution.of(Action.MANAGE_SESSIONS, ResourceRef.session(null));
    }
    if (isUnder(p, PATH_AGENTS)) {
      return readOrManage(
          m, Action.MANAGE_AGENTS, ResourceRef.agent(segmentAfter(p, PATH_AGENTS_PREFIX)));
    }
    if (isUnder(p, PATH_KNOWLEDGE)) {
      return readOrManage(
          m,
          Action.MANAGE_KNOWLEDGE,
          ResourceRef.knowledge(segmentAfter(p, PATH_KNOWLEDGE_PREFIX)));
    }
    if (isUnder(p, PATH_SKILLS)) {
      return readOrManage(
          m, Action.MANAGE_SKILLS, ResourceRef.skill(segmentAfter(p, PATH_SKILLS_PREFIX)));
    }
    if (isUnder(p, PATH_PERSONAS) || isUnder(p, PATH_SCHEDULES)) {
      return readOrManage(m, Action.MANAGE_AGENTS, ResourceRef.agent(null));
    }
    // v2 调度：与 v1 schedules 同档（盘点缺口补登记）
    if (isUnder(p, PATH_V2_SCHEDULES) || matchesV2AgentScheduleRun(p)) {
      return readOrManage(m, Action.MANAGE_AGENTS, ResourceRef.agent(null));
    }
    if (isChannelManagePath(p)) {
      return Resolution.of(Action.MANAGE_CHANNELS, ResourceRef.channel(null));
    }
    if (isUnder(p, PATH_TOOL_POLICY) || isUnder(p, PATH_SANDBOX) || isUnder(p, PATH_APPROVALS)) {
      return Resolution.of(Action.MANAGE_POLICIES, ResourceRef.policy());
    }
    // 审计面（llm_calls / tool_invocations）现有 handler 全是只读查询：GET/HEAD 为 READ_AUDIT，
    // 其余方法保持未登记（fail-closed），与 /api/v1/cost 同款。只按前缀登记会让将来加在审计前缀下的写端点
    // 静默继承 VIEWER 可用的 READ_AUDIT；未登记的写端点则先被启动期覆盖闸（RbacEndpointCoverageCheck）拦下，
    // 必须显式登记到更严的动作。
    if (isUnder(p, PATH_AUDIT)) {
      if (isRead(m)) {
        return Resolution.of(Action.READ_AUDIT, ResourceRef.audit());
      }
      return null;
    }
    if (isUnder(p, PATH_WORKSPACE)) {
      return readOrManage(m, Action.MANAGE_WORKSPACE, ResourceRef.workspace());
    }
    if (isUnder(p, PATH_PROVIDERS) || isUnder(p, PATH_PRICING)) {
      return readOrManage(m, Action.MANAGE_WORKSPACE, ResourceRef.workspace());
    }
    // 成本看板（053）：全部只读查询，VIEWER 可读（READ_WORKSPACE）；非读方法拒绝。
    // 前缀登记覆盖现有 /attribution、/runs/*/reconcile 与新增 /daily、/tool-usage、/facets，
    // 避免 fail-closed 下未登记路径被 403。
    if (isUnder(p, PATH_COST)) {
      if (isRead(m)) {
        return Resolution.of(Action.READ_WORKSPACE, ResourceRef.workspace());
      }
      return null;
    }
    if (isReadWorkspaceExactOrUnder(p)) {
      if (isRead(m)) {
        return Resolution.of(Action.READ_WORKSPACE, ResourceRef.workspace());
      }
      return null;
    }
    // teams / orgs / user team memberships / identity-mappings：ADMIN-only（MANAGE_MEMBERS）；含 GET
    if (isUnder(p, PATH_TEAMS)
        || isUnder(p, PATH_ORGS)
        || isUnder(p, PATH_IDENTITY_MAPPINGS)
        || isUserTeamsPath(p)) {
      return Resolution.of(Action.MANAGE_MEMBERS, ResourceRef.member(null));
    }
    // 039 SC-005 覆盖补登记：capabilities / routing / evals / flows 四个控制器一直无条件注册，
    // team-tasks 则由 @ConditionalOnProperty(oryxos.task.team.enabled) 开关控制 —— 五个前缀都从未登记进本表，
    // 于是 oryxos.web.rbac.enabled=true 时 RbacEndpointCoverageCheck 在启动期直接抛异常（fail-closed 兜不住启动）。
    // team-tasks 那三个尤其隐蔽：默认部署下它根本不注册，"打开 team tasks + 打开 RBAC"才会撞上。
    // 分档：capabilities 与 routing 是只读目录，非读方法保持未登记（继续 fail-closed）；evals 读的是审计两表
    // （llm_calls / tool_invocations），与 /audit 同口径——只读方法才登记，非读保持未登记；flows/generate 与
    // team-tasks 的写方法会驱动模型/Agent 真干活，按 RUN_AGENT（VIEWER 不该能触发）。
    if (isUnder(p, PATH_CAPABILITIES) || isUnder(p, PATH_ROUTING)) {
      if (isRead(m)) {
        return Resolution.of(Action.READ_WORKSPACE, ResourceRef.workspace());
      }
      return null;
    }
    // evals 与 /audit 同一张审计面：现有 handler 全为只读导出，非读方法未登记 → 启动期覆盖闸拦下（fail-closed），
    // 不会让将来加在 /api/v1/evals 下的写端点继承 VIEWER 可用的 READ_AUDIT。
    if (isUnder(p, PATH_EVALS)) {
      if (isRead(m)) {
        return Resolution.of(Action.READ_AUDIT, ResourceRef.audit());
      }
      return null;
    }
    if (isUnder(p, PATH_FLOWS) || isUnder(p, PATH_TEAM_TASKS)) {
      return readOrManage(m, Action.RUN_AGENT, ResourceRef.agent(null));
    }
    // 未登记：fail-closed
    return null;
  }

  /** /api/v1/users/{username}/teams[/{teamId}] */
  private static boolean isUserTeamsPath(String path) {
    String prefix = PATH_USERS + PATH_SEP;
    if (!path.startsWith(prefix)) {
      return false;
    }
    String rest = path.substring(prefix.length());
    int slash = rest.indexOf(PATH_SEP);
    if (slash < 0) {
      return false;
    }
    String afterUser = rest.substring(slash);
    return SEGMENT_TEAMS.equals(afterUser) || afterUser.startsWith(SEGMENT_TEAMS + PATH_SEP);
  }

  private static boolean isSkipPath(String path) {
    boolean healthOrAuth =
        isExact(path, PATH_API_V1_HEALTH)
            || isUnder(path, PATH_API_V1_AUTH)
            || isExact(path, PATH_ACTUATOR_HEALTH)
            || isUnder(path, PATH_ACTUATOR_HEALTH);
    return healthOrAuth;
  }

  private static boolean isAlipayCompatGateway(String method, String path) {
    boolean alipayRoot = isExact(path, PATH_API_V1) || isExact(path, PATH_API_V1_SLASH);
    return alipayRoot && HttpMethod.POST.matches(method);
  }

  private static boolean isChannelManagePath(String path) {
    return isUnder(path, PATH_CHANNELS)
        || isUnder(path, PATH_NOTIFY_CHANNELS)
        || isUnder(path, PATH_MCP_SERVERS);
  }

  private static boolean isReadWorkspaceExactOrUnder(String path) {
    return isExact(path, PATH_API_V1_INFO)
        || isUnder(path, PATH_PROFILES)
        || isUnder(path, PATH_TOOLS)
        || isUnder(path, PATH_INSTANCES);
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
    if (!isUnder(path, PATH_AGENTS)) {
      return false;
    }
    return path.endsWith(SUFFIX_INVOKE);
  }

  private static boolean matchesV2AgentScheduleRun(String path) {
    // /api/v2/agents/{profile}/schedules/{key}/run
    boolean v2Agent = path.startsWith(PATH_V2_AGENTS_PREFIX);
    boolean hasSchedule = path.contains(SEGMENT_SCHEDULES);
    boolean runSuffix = path.endsWith(SUFFIX_RUN);
    return v2Agent && hasSchedule && runSuffix;
  }

  private static String segmentAfter(String path, String prefix) {
    if (!path.startsWith(prefix) || path.length() <= prefix.length()) {
      return null;
    }
    String rest = path.substring(prefix.length());
    int slash = rest.indexOf(PATH_SEP);
    String seg = slash < 0 ? rest : rest.substring(0, slash);
    return seg.isBlank() ? null : seg;
  }

  private static String normalize(String path) {
    if (path == null || path.isBlank()) {
      return "";
    }
    // 去掉查询串（若调用方误传）
    int q = path.indexOf(QUERY_MARK);
    String p = q < 0 ? path : path.substring(0, q);
    if (p.length() > 1 && p.endsWith(PATH_SEP)) {
      // 保留 "/api/v1/" 这种兼容根；其它尾斜杠去掉以便前缀匹配
      if (!isExact(p, PATH_API_V1_SLASH) && !isExact(p, PATH_API_V1)) {
        return p.substring(0, p.length() - 1);
      }
    }
    return p;
  }

  private static boolean isExact(String path, String expected) {
    return expected.equals(path);
  }

  private static boolean isUnder(String path, String prefix) {
    return path.equals(prefix) || path.startsWith(prefix + PATH_SEP);
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
