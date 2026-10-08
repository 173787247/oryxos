package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.core.policy.ApprovalAuditKind;
import io.oryxos.core.policy.ApprovalAuditRecorder;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 042 审批审计（#464）的列宽契约：`approval_events` 的文本列在两种 vendor 的迁移里都带宽度（{@code reason VARCHAR(1024)}、{@code
 * actor VARCHAR(128)}、{@code session_id VARCHAR(128)}……），SQLite 不校验长度、PostgreSQL 会拒绝超长值。而 recorder
 * 是 fail-open 的：写失败只记 ERROR，不向调用方抛。
 *
 * <p>两件事叠起来就是一个静默的洞——超长的审批意见在 PG 档会让**整条审批决策从审计里消失**，调用方还拿到成功。同模块其它审计 recorder（AuthzEventRecorder /
 * AuthEventRecorder / AssetGovernanceEventRecorder /
 * AssetGovernanceRevisionRecorder）都先按列宽截断再落库，这里漏了。
 */
@org.springframework.transaction.annotation.Transactional
abstract class ApprovalAuditTruncationContractTest {

  /** 与 {@code V18__approval_events.sql} / {@code ApprovalEventsMigration} 的列宽一一对应。 */
  private static final int MAX_REASON = 1024;

  @Autowired private ApprovalEventRepository repository;

  private ApprovalAuditRecorder recorder() {
    return new JpaApprovalAuditRecorder(repository);
  }

  private static ApprovalAuditRecorder.ApprovalAuditEvent event(String reason, String actor) {
    return new ApprovalAuditRecorder.ApprovalAuditEvent(
        ApprovalAuditKind.APPROVED,
        "session-1",
        "agent-1",
        "tool-1",
        null,
        "policy-v1",
        "rule-1",
        actor,
        reason,
        null);
  }

  @Test
  @DisplayName("超长审批意见：这一条决策仍必须落进审计（截断，而不是丢掉整行）")
  void anOverlongReasonStillLeavesAnAuditRow() {
    String longReason = "人工审批意见很长。".repeat(300); // 2700 字符 > 1024

    recorder().record(event(longReason, "alice"));

    List<ApprovalEvent> rows = repository.findAll();
    assertThat(rows).as("审计失败是 fail-open 的，但这一行不该因此消失").hasSize(1);
    assertThat(rows.get(0).getReason()).hasSizeLessThanOrEqualTo(MAX_REASON);
  }

  @Test
  @DisplayName("其余文本列超长同样不得让整行消失")
  void otherOverlongColumnsDoNotDropTheRowEither() {
    ApprovalAuditRecorder.ApprovalAuditEvent oversized =
        new ApprovalAuditRecorder.ApprovalAuditEvent(
            ApprovalAuditKind.APPROVED,
            "s".repeat(200),
            "a".repeat(300),
            "t".repeat(300),
            "x".repeat(80),
            "p".repeat(80),
            "r".repeat(200),
            "u".repeat(200),
            "理由",
            null);

    recorder().record(oversized);

    assertThat(repository.findAll()).hasSize(1);
  }

  @Test
  @DisplayName("反向：正常长度的事件逐字落库，不被打断或改写")
  void normalLengthEventsAreStoredVerbatim() {
    recorder().record(event("同意，风险可控", "alice"));

    List<ApprovalEvent> rows = repository.findAll();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getReason()).isEqualTo("同意，风险可控");
    assertThat(rows.get(0).getActor()).isEqualTo("alice");
    assertThat(rows.get(0).getKind()).isEqualTo(ApprovalAuditKind.APPROVED.name());
  }
}
