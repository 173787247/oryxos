package io.oryxos.core.policy;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 {@link ApprovalPolicyConfig} 的审批策略实现（042 / #464）：纯函数裁决 + 审计旁路。
 *
 * <p>关闭时恒 {@link ApprovalOutcome#ALLOW}。开启后按规则顺序匹配 Agent / 工具 / 动作类型。
 */
public final class ConfigApprovalPolicyServiceImpl implements ApprovalPolicyService {

  private static final Logger LOG = LoggerFactory.getLogger(ConfigApprovalPolicyServiceImpl.class);

  private final ApprovalPolicyConfig config;
  private final HighRiskActionClassifier classifier;
  private final Function<String, String> mcpOwnerLookup;
  private final ApprovalAuditRecorder audit;

  public ConfigApprovalPolicyServiceImpl(ApprovalPolicyConfig config) {
    this(config, new HighRiskActionClassifier(), name -> null, ApprovalAuditRecorder.NOOP);
  }

  public ConfigApprovalPolicyServiceImpl(
      ApprovalPolicyConfig config,
      HighRiskActionClassifier classifier,
      Function<String, String> mcpOwnerLookup,
      ApprovalAuditRecorder audit) {
    this.config = Objects.requireNonNullElseGet(config, ApprovalPolicyConfig::disabled);
    this.classifier =
        classifier == null ? new HighRiskActionClassifier(mcpOwnerLookup) : classifier;
    this.mcpOwnerLookup = mcpOwnerLookup == null ? name -> null : mcpOwnerLookup;
    this.audit = audit == null ? ApprovalAuditRecorder.NOOP : audit;
  }

  @Override
  public ApprovalPolicyDecision evaluate(String agentName, String toolName, String argumentsJson) {
    if (!config.enabled()) {
      return ApprovalPolicyDecision.allow(config.policyVersion());
    }
    HighRiskActionType actionType = classifier.classify(toolName).orElse(null);
    String mcpServer = mcpOwnerLookup.apply(toolName);
    for (ApprovalPolicyConfig.Rule rule : config.rules()) {
      if (!rule.hasSelector()) {
        LOG.warn("审批规则 {} 无 tools/actionTypes 选择器，已跳过", rule.id());
        continue;
      }
      if (!rule.matchesAgent(agentName)) {
        continue;
      }
      // 仅有 actionTypes、无 tools：靠分类命中；仅有 tools：靠工具名；两者皆有：都需命中
      boolean toolOk = rule.tools().isEmpty() || rule.matchesTool(toolName, mcpServer);
      boolean typeOk = rule.actionTypes().isEmpty() || rule.matchesActionType(actionType);
      if (!(toolOk && typeOk)) {
        continue;
      }
      // 仅配了 actionTypes 却分不出类型 → 不命中（避免误伤）
      if (!rule.actionTypes().isEmpty() && actionType == null) {
        continue;
      }
      List<String> approvers =
          rule.approvers().isEmpty() ? config.defaultApprovers() : rule.approvers();
      int ttl = rule.ttlSeconds() != null ? rule.ttlSeconds() : config.defaultTtlSeconds();
      ApprovalOutcome effect = rule.effect();
      String reason =
          effect == ApprovalOutcome.DENY
              ? "命中审批拒绝规则（" + rule.id() + "）"
              : "命中高风险审批规则（" + rule.id() + "）";
      return new ApprovalPolicyDecision(
          effect, config.policyVersion(), rule.id(), actionType, approvers, ttl, reason);
    }
    return ApprovalPolicyDecision.allow(config.policyVersion());
  }

  @Override
  public boolean recordHumanDecision(ApprovalHumanDecision decision) {
    if (!config.enabled() || decision == null) {
      return false;
    }
    ApprovalAuditKind kind =
        decision.approved() ? ApprovalAuditKind.APPROVED : ApprovalAuditKind.DENIED;
    try {
      audit.record(
          new ApprovalAuditRecorder.ApprovalAuditEvent(
              kind,
              decision.sessionId(),
              decision.agentName(),
              decision.toolName(),
              null,
              decision.policyVersion() != null ? decision.policyVersion() : config.policyVersion(),
              decision.ruleId(),
              decision.actor() == null || decision.actor().isBlank() ? "unknown" : decision.actor(),
              decision.comment(),
              null));
      return true;
    } catch (RuntimeException ex) {
      LOG.error("审批最终决策审计失败（不阻断）: tool={}", decision.toolName(), ex);
      return false;
    }
  }

  @Override
  public void recordHit(
      String sessionId, String agentName, String toolName, ApprovalPolicyDecision decision) {
    if (decision == null || decision.allowed()) {
      return;
    }
    ApprovalAuditKind kind =
        decision.denied() ? ApprovalAuditKind.HIT_DENY : ApprovalAuditKind.HIT_REQUIRE;
    try {
      audit.record(
          new ApprovalAuditRecorder.ApprovalAuditEvent(
              kind,
              sessionId,
              agentName,
              toolName,
              decision.actionType() == null ? null : decision.actionType().name(),
              decision.policyVersion(),
              decision.ruleId(),
              "system",
              decision.reason(),
              decision.ttlSeconds()));
    } catch (RuntimeException ex) {
      LOG.error("审批命中审计失败（不阻断）: rule={}", decision.ruleId(), ex);
    }
  }

  public ApprovalPolicyConfig config() {
    return config;
  }
}
