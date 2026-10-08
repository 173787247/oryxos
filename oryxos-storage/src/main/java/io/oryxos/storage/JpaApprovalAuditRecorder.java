package io.oryxos.storage;

import io.oryxos.core.policy.ApprovalAuditRecorder;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 审批审计落库（042 / #464）：写入失败记 ERROR，不向调用方抛（fail-open）。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "CRLF_INJECTION_LOGS",
    justification = "Log arg is ApprovalAuditKind enum name from trusted code path.")
public final class JpaApprovalAuditRecorder implements ApprovalAuditRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(JpaApprovalAuditRecorder.class);

  // 与 V18__approval_events.sql / ApprovalEventsMigration 的列宽逐列对齐。SQLite 不校验 VARCHAR 宽度、
  // PostgreSQL 会拒绝超长值，而本类是 fail-open 的：不截断的话，一条超长审批意见会让【整行】插入失败、
  // 这条决策从审计里消失，调用方却拿到成功。同模块其它审计 recorder 都先按列宽截断。
  private static final int MAX_KIND = 32;
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
      row.setKind(truncate(event.kind().name(), MAX_KIND));
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

  /** 按列宽截断（同 AuthzEventRecorder）：宁可写下一个被截断的值，也不让整行因为一个超长字段消失。 */
  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
