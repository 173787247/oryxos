package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.core.policy.ApprovalAuditKind;
import io.oryxos.core.policy.ApprovalAuditRecorder.ApprovalAuditEvent;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JpaApprovalAuditRecorder 落库宽度契约（042 / #464，两库同套用例）。
 *
 * <p>approval_events 的列宽由 V18 唯一规定（{@code actor VARCHAR(128)} / {@code reason VARCHAR(1024)} /
 * …），实体 {@link ApprovalEvent} 的 {@code @Column(length = …)} 与之一致，同模块其余 recorder 也都在落库前按各自列宽截断。
 * 本用例钉死：审计字段里的自由文本超长时必须被有界化后**仍然落库**，而不是在 PostgreSQL 上被拒、再由 fail-open 的 catch
 * 静默吞掉——那样这一条审批决策在审计里就不存在了（{@code specs/042-hitl-approval-policy/spec.md} 要求命中与决策被完整审计）。
 *
 * <p>NOT_SUPPORTED 压制测试事务：插入被数据库拒绝后，若身处外层事务会被标记 rollback-only。生产 recorder 由调用方
 * 直接调用、没有外层事务，测试按同形态运行。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class JpaApprovalAuditRecorderContractTest {

  private static final int ACTOR_LIMIT = 128;
  private static final int SESSION_LIMIT = 128;
  private static final int REASON_LIMIT = 1024;

  @Autowired private ApprovalEventRepository repository;

  private JpaApprovalAuditRecorder recorder;

  @BeforeEach
  void setUp() {
    recorder = new JpaApprovalAuditRecorder(repository);
    repository.deleteAll();
  }

  private static ApprovalAuditEvent event(String sessionId, String actor, String reason) {
    return new ApprovalAuditEvent(
        ApprovalAuditKind.HIT_DENY,
        sessionId,
        "agent",
        "tool",
        "action",
        "v1",
        "rule",
        actor,
        reason,
        60);
  }

  @Test
  @DisplayName("record_超长自由文本按列宽截断_该条审批审计仍然落库")
  void record_truncatesOverlongFreeTextAndKeepsTheRow() {
    String longReason = "拒绝理由:".repeat(400);
    String longSession = "s".repeat(200);
    String longActor = "审".repeat(200);

    recorder.record(event(longSession, longActor, longReason));

    List<ApprovalEvent> rows = repository.findAll();
    // PostgreSQL 上的缺陷形态：插入被列宽拒绝 → 被 fail-open 吞掉 → 这里一行都没有。
    assertThat(rows).hasSize(1);
    ApprovalEvent row = rows.get(0);
    assertThat(row.getReason()).hasSize(REASON_LIMIT);
    assertThat(row.getSessionId()).hasSize(SESSION_LIMIT);
    assertThat(row.getActor()).hasSize(ACTOR_LIMIT);
    // 截断保留前缀，不能变成别的内容。
    assertThat(row.getReason()).isEqualTo(longReason.substring(0, REASON_LIMIT));
    assertThat(row.getSessionId()).isEqualTo(longSession.substring(0, SESSION_LIMIT));
    assertThat(row.getActor()).isEqualTo(longActor.substring(0, ACTOR_LIMIT));
  }

  @Test
  @DisplayName("record_长度恰在界内的值与短值都原样落库_不提前截断")
  void record_keepsValuesWithinBoundsVerbatim() {
    String reasonAtLimit = "r".repeat(REASON_LIMIT);
    String sessionAtLimit = "s".repeat(SESSION_LIMIT);
    String actorAtLimit = "a".repeat(ACTOR_LIMIT);

    recorder.record(event(sessionAtLimit, actorAtLimit, reasonAtLimit));

    List<ApprovalEvent> rows = repository.findAll();
    assertThat(rows).hasSize(1);
    ApprovalEvent row = rows.get(0);
    assertThat(row.getReason()).isEqualTo(reasonAtLimit);
    assertThat(row.getSessionId()).isEqualTo(sessionAtLimit);
    assertThat(row.getActor()).isEqualTo(actorAtLimit);
  }
}
