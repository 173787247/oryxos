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
      row.setSessionId(event.sessionId());
      row.setAgentName(event.agentName());
      row.setToolName(event.toolName());
      row.setActionType(event.actionType());
      row.setPolicyVersion(event.policyVersion());
      row.setRuleId(event.ruleId());
      row.setActor(event.actor() == null || event.actor().isBlank() ? "system" : event.actor());
      row.setReason(event.reason());
      row.setTtlSeconds(event.ttlSeconds());
      row.setCreatedAt(Instant.now());
      repository.save(row);
    } catch (RuntimeException ex) {
      LOG.error("approval_events 落库失败: kind={}", event.kind(), ex);
    }
  }
}
