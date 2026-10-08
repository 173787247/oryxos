package io.oryxos.storage;

import io.oryxos.core.policy.ApprovalAuditRecorder;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审批审计落库（042 / #464）：写入失败记 ERROR，不向调用方抛（fail-open）。
 *
 * <p>{@code approval_events} 的列宽由 V18 迁移唯一规定（见实体上的 {@code @Column(length = …)}）。自由文本在落库前
 * 按列宽截断：fail-open 会把「插入被数据库拒绝」变成「这一条审批决策在审计里不存在」，而列宽是 SQLite 不校验、 PostgreSQL
 * 校验的——同一份代码在默认档看不出问题。同模块其余 recorder（Authz/Auth/AssetGovernance）同样先截断。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "CRLF_INJECTION_LOGS",
    justification = "Log arg is ApprovalAuditKind enum name from trusted code path.")
public final class JpaApprovalAuditRecorder implements ApprovalAuditRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(JpaApprovalAuditRecorder.class);

  private static final int MAX_SESSION_ID = 128;
  private static final int MAX_AGENT_NAME = 255;
  private static final int MAX_TOOL_NAME = 255;
  private static final int MAX_ACTION_TYPE = 64;
  private static final int MAX_POLICY_VERSION = 64;
  private static final int MAX_RULE_ID = 128;
  private static final int MAX_ACTOR = 128;
  private static final int MAX_REASON = 1024;

  private final ApprovalEventRepository repository;

  public JpaApprovalAuditRecorder(ApprovalEventRepository repository) {
    this.repository = repository;
  }

  @Override
  public void record(ApprovalAuditEvent event) {
    if (event == null || event.kind() == null) {
      return;
    }
    try {
      ApprovalEvent row = new ApprovalEvent();
      row.setKind(event.kind().name());
      row.setSessionId(truncate(event.sessionId(), MAX_SESSION_ID));
      row.setAgentName(truncate(event.agentName(), MAX_AGENT_NAME));
      row.setToolName(truncate(event.toolName(), MAX_TOOL_NAME));
      row.setActionType(truncate(event.actionType(), MAX_ACTION_TYPE));
      row.setPolicyVersion(truncate(event.policyVersion(), MAX_POLICY_VERSION));
      row.setRuleId(truncate(event.ruleId(), MAX_RULE_ID));
      row.setActor(
          truncate(
              event.actor() == null || event.actor().isBlank() ? "system" : event.actor(),
              MAX_ACTOR));
      row.setReason(truncate(event.reason(), MAX_REASON));
      row.setTtlSeconds(event.ttlSeconds());
      row.setCreatedAt(Instant.now());
      repository.save(row);
    } catch (RuntimeException ex) {
      LOG.error("approval_events 落库失败: kind={}", event.kind(), ex);
    }
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
