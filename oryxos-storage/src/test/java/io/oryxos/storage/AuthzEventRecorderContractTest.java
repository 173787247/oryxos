package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.policy.Action;
import io.oryxos.core.policy.ResourceRef;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * AuthzEventRecorder 落库宽度契约（039，两库同套用例）。
 *
 * <p>authz_events 的列宽由 V9 唯一规定（{@code resource_id VARCHAR(255)} 等），实体 {@link AuthzEvent} 的
 * {@code @Column(length = …)} 与之一致。本用例钉死：审计字段里的自由文本超长时必须被有界化后**仍然落库**，而不是在 PostgreSQL 上被列宽拒绝、 再由
 * recorder 的 fail-open catch 静默吞掉——那样这一条拒绝裁决在审计里就不存在了，而 039 要求的正是「拒绝行为明确且有审计」。
 *
 * <p>resourceId 是本表唯一能装下任意长度外部输入的字段：它直接来自请求路径段（{@code RequestActionResolver} 把 {@code
 * /api/agents/<name>} 的 name 原样放进 {@link ResourceRef}），调用方无法预知长度。同方法其余字段要么是枚举名，要么已按列宽截断。
 *
 * <p>NOT_SUPPORTED 压制测试事务：被数据库拒绝的插入若身处外层事务会把它标记为 rollback-only。生产 recorder 由调用方直接调用、
 * 没有外层事务，测试按同形态运行。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class AuthzEventRecorderContractTest {

  private static final int RESOURCE_ID_LIMIT = 255;
  private static final String PATH = "/api/agents";

  @Autowired private AuthzEventRepository repository;

  private AuthzEventRecorder recorder;

  @BeforeEach
  void setUp() {
    recorder = new AuthzEventRecorder(repository);
    repository.deleteAll();
  }

  @Test
  @DisplayName("record_超长 resourceId 按列宽截断_该条拒绝审计仍然落库")
  void recordTruncatesOverlongResourceIdAndKeepsTheRow() {
    String longResourceId = "agent-" + "x".repeat(400);

    recorder.record(
        Principal.anonymous(),
        Action.RUN_AGENT,
        ResourceRef.agent(longResourceId),
        "越权调用",
        "POST",
        PATH);

    List<AuthzEvent> rows = repository.findAll();
    // PostgreSQL 上的缺陷形态：INSERT 被列宽拒绝 → 被 fail-open 吞掉 → 这里一行都没有。
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getResourceId()).hasSize(RESOURCE_ID_LIMIT);
    // 截断保留前缀，不能变成别的内容。
    assertThat(rows.get(0).getResourceId())
        .isEqualTo(longResourceId.substring(0, RESOURCE_ID_LIMIT));
  }

  @Test
  @DisplayName("record_长度恰在界内的 resourceId 原样落库_不提前截断")
  void recordKeepsResourceIdAtTheLimitVerbatim() {
    String resourceIdAtLimit = "m".repeat(RESOURCE_ID_LIMIT);

    recorder.record(
        Principal.anonymous(),
        Action.MANAGE_AGENTS,
        ResourceRef.agent(resourceIdAtLimit),
        "越权调用",
        "DELETE",
        PATH);

    List<AuthzEvent> rows = repository.findAll();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getResourceId()).isEqualTo(resourceIdAtLimit);
  }

  @Test
  @DisplayName("record_超长主体与理由一并落库_同方法其它字段的有界化不被 resourceId 的修复破坏")
  void recordBoundsTheSiblingFieldsToo() {
    recorder.record(
        Principal.apiKey("k".repeat(200), null),
        Action.MANAGE_CHANNELS,
        ResourceRef.channel("c".repeat(400)),
        "r".repeat(900),
        "GET",
        "/" + "p".repeat(900));

    List<AuthzEvent> rows = repository.findAll();
    assertThat(rows).hasSize(1);
    AuthzEvent row = rows.get(0);
    assertThat(row.getPrincipalId()).hasSize(128);
    assertThat(row.getReason()).hasSize(512);
    assertThat(row.getRequestPath()).hasSize(512);
    assertThat(row.getResourceId()).hasSize(RESOURCE_ID_LIMIT);
  }
}
