package io.oryxos.web.controller;

import io.oryxos.core.OryxTool;
import io.oryxos.core.policy.ToolPolicyService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.storage.ToolPolicyRule;
import io.oryxos.storage.ToolPolicyRuleRepository;
import io.oryxos.storage.WebSessionService;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.error.ResourceNotFoundException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具策略端点（020-tool-policy，契约见 specs/020 contracts/policy-api.md §3）：规则 CRUD + 每个 Agent 的
 * 有效工具集视图（含被移除工具的命中规则描述——与执行面共用 {@link ToolPolicyService#check} 单一裁决逻辑）。
 *
 * <p>created_by 取管理台 session 用户名（012 的 oryxos_session cookie），无认证部署记 anonymous——「配置即责任」
 * 最低追溯口径。unknownTarget 仅对精确名工具判定（通配的未知 server 由启动检查 ToolPolicyStartupCheck 告警—— web 模块无工具归属表）。镜像
 * SandboxWhitelistController 的治理端点形态。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "治理端点与其余 /api/v1 一致受 018 门禁保护（默认内网）；注入的 repository/policy/registry/tools 均为共享单例"
            + "活视图，存同一引用正是意图（镜像 ToolApiController 模式）。")
@RestController
@RequestMapping("/api/v1/tool-policy")
public class PolicyApiController {

  private static final String SESSION_COOKIE = "oryxos_session";
  private static final String ANONYMOUS = "anonymous";
  private static final String WILDCARD_SUFFIX = ":*";

  private final ToolPolicyRuleRepository repository;
  private final ToolPolicyService policy;
  private final ProfileRegistry profileRegistry;
  private final Map<String, OryxTool> tools;
  private final WebSessionService sessionService;

  public PolicyApiController(
      ToolPolicyRuleRepository repository,
      ToolPolicyService policy,
      ProfileRegistry profileRegistry,
      @Qualifier("tools") Map<String, OryxTool> tools,
      WebSessionService sessionService) {
    this.repository = repository;
    this.policy = policy;
    this.profileRegistry = profileRegistry;
    this.tools = tools;
    this.sessionService = sessionService;
  }

  /** 规则请求体。 */
  public record CreateRuleRequest(String ruleType, String agentName, String pattern) {}

  /** 规则视图（unknownTarget：精确名工具未注册时 true，提示可能拼写有误）。 */
  public record PolicyRuleView(
      Long id,
      String ruleType,
      String agentName,
      String pattern,
      java.time.Instant createdAt,
      String createdBy,
      boolean unknownTarget) {}

  /** 被策略移除的工具及原因。 */
  public record RemovedToolView(String toolName, String reason) {}

  /** 某 Agent 的有效工具集视图（copyOf 保不可变，SpotBugs EI_EXPOSE_REP）。 */
  public record EffectiveToolSetView(
      String agentName,
      List<String> declared,
      List<String> effective,
      List<RemovedToolView> removed) {

    public EffectiveToolSetView {
      declared = List.copyOf(declared);
      effective = List.copyOf(effective);
      removed = List.copyOf(removed);
    }
  }

  /** 全量视图：规则 + 各 Agent 有效集（copyOf 保不可变）。 */
  public record PolicyOverview(List<PolicyRuleView> rules, List<EffectiveToolSetView> effective) {

    public PolicyOverview {
      rules = List.copyOf(rules);
      effective = List.copyOf(effective);
    }
  }

  @GetMapping
  public ApiResponse<PolicyOverview> overview() {
    List<PolicyRuleView> rules = repository.findAll().stream().map(this::toView).toList();
    List<EffectiveToolSetView> effective = new ArrayList<>();
    for (Profile profile : profileRegistry.all()) {
      effective.add(effectiveFor(profile));
    }
    effective.sort(java.util.Comparator.comparing(EffectiveToolSetView::agentName));
    return ApiResponse.ok(new PolicyOverview(rules, effective));
  }

  @PostMapping("/rules")
  public ResponseEntity<ApiResponse<PolicyRuleView>> create(
      @RequestBody CreateRuleRequest req, HttpServletRequest request) {
    ToolPolicyRule.RuleType type = parseType(req);
    validateAgentName(type, req);
    if (req.pattern() == null || req.pattern().isBlank()) {
      throw new IllegalArgumentException("pattern 不能为空"); // → 400
    }
    String agentName = type == ToolPolicyRule.RuleType.GLOBAL_DENY ? null : req.agentName().strip();
    String pattern = req.pattern().strip();
    if (repository.existsByRuleTypeAndAgentNameAndPattern(type.name(), agentName, pattern)) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(ApiResponse.error(HttpStatus.CONFLICT.value(), "规则已存在"));
    }
    ToolPolicyRule rule = new ToolPolicyRule();
    rule.setRuleType(type.name());
    rule.setAgentName(agentName);
    rule.setPattern(pattern);
    rule.setCreatedBy(currentOperator(request));
    ToolPolicyRule saved = repository.save(rule);
    return ResponseEntity.ok(ApiResponse.ok(toView(saved)));
  }

  @DeleteMapping("/rules/{id}")
  public ApiResponse<Void> delete(@PathVariable Long id) {
    if (!repository.existsById(id)) {
      throw new ResourceNotFoundException("策略规则不存在: #" + id); // → 404
    }
    repository.deleteById(id);
    return ApiResponse.ok(null);
  }

  private EffectiveToolSetView effectiveFor(Profile profile) {
    List<String> declared = profile.tools();
    List<String> effective = new ArrayList<>();
    List<RemovedToolView> removed = new ArrayList<>();
    for (String toolName : declared) {
      ToolPolicyService.PolicyDecision decision = policy.check(profile.name(), toolName);
      if (decision.allowed()) {
        effective.add(toolName);
      } else {
        removed.add(new RemovedToolView(toolName, decision.reason()));
      }
    }
    return new EffectiveToolSetView(profile.name(), declared, effective, removed);
  }

  private PolicyRuleView toView(ToolPolicyRule rule) {
    boolean unknown =
        rule.getPattern() != null
            && !rule.getPattern().endsWith(WILDCARD_SUFFIX)
            && !tools.containsKey(rule.getPattern());
    return new PolicyRuleView(
        rule.getId(),
        rule.getRuleType(),
        rule.getAgentName(),
        rule.getPattern(),
        rule.getCreatedAt(),
        rule.getCreatedBy(),
        unknown);
  }

  private static ToolPolicyRule.RuleType parseType(CreateRuleRequest req) {
    if (req == null || req.ruleType() == null) {
      throw new IllegalArgumentException("ruleType 不能为空"); // → 400
    }
    try {
      return ToolPolicyRule.RuleType.valueOf(req.ruleType());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "ruleType 非法，应为 GLOBAL_DENY / AGENT_EXEMPT / AGENT_DENY"); // → 400
    }
  }

  private static void validateAgentName(ToolPolicyRule.RuleType type, CreateRuleRequest req) {
    boolean hasAgent = req.agentName() != null && !req.agentName().isBlank();
    if (type == ToolPolicyRule.RuleType.GLOBAL_DENY && hasAgent) {
      throw new IllegalArgumentException("GLOBAL_DENY 不得携带 agentName"); // → 400
    }
    if (type != ToolPolicyRule.RuleType.GLOBAL_DENY && !hasAgent) {
      throw new IllegalArgumentException(type.name() + " 必须携带 agentName"); // → 400
    }
  }

  /** 变更来源：管理台 session 用户名，无认证部署记 anonymous（FR-013 最低追溯口径）。 */
  private String currentOperator(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return ANONYMOUS;
    }
    return Arrays.stream(cookies)
        .filter(c -> SESSION_COOKIE.equals(c.getName()))
        .map(Cookie::getValue)
        .findFirst()
        .flatMap(sessionService::findValid)
        .map(s -> s.getUsername())
        .orElse(ANONYMOUS);
  }
}
