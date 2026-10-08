package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.core.policy.AssetGovernance;
import io.oryxos.core.policy.AssetGovernanceStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 治理快照落库契约（#537 / #541）。
 *
 * <p>{@code snapshot_text} 不是旁路日志：{@code AssetGovernanceApiSupport.restore} 把它当权威内容 {@code
 * parseSnapshotYaml} 后写回现网。落到库里的文本又必须能解析回落库时的治理。落库链上唯一的 「有界化」在 recorder 里，而它是静默的——一旦截断，restore
 * 拿到的是一份**残缺但语法合法**的治理 （owner 变短、其后的 version/visibility/riskLevel/health 全丢），会照它写回现网。
 */
@SqliteJpaTest
class AssetGovernanceRevisionRecorderSnapshotTest {

  @TempDir static Path dbDir;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("test.db"));
  }

  @Autowired private AssetGovernanceRevisionRepository repository;

  private AssetGovernanceRevisionRecorder recorder;

  @BeforeEach
  void setUp() {
    recorder = new AssetGovernanceRevisionRecorder(repository);
    repository.deleteAll();
  }

  @Test
  @DisplayName("record_超长快照落库后仍能解析回落库时的治理")
  void oversizedSnapshotStillParsesBackToTheRecordedGovernance() {
    AssetGovernance governance =
        new AssetGovernance(
            "a".repeat(70_000),
            "v7",
            AssetGovernance.Visibility.PUBLIC,
            "low",
            AssetGovernance.Health.ACTIVE,
            null,
            null);

    recorder.record(
        "admin", "agent", "agent-1", "v7", AssetGovernanceStore.snapshotYaml(governance));

    List<AssetGovernanceRevision> rows = repository.findAll();
    assertThat(rows).hasSize(1);
    AssetGovernance restored =
        AssetGovernanceStore.parseSnapshotYaml(rows.get(0).getSnapshotText());
    assertThat(restored.visibility()).isEqualTo(governance.visibility());
    assertThat(restored.health()).isEqualTo(governance.health());
    assertThat(restored.version()).isEqualTo(governance.version());
    assertThat(restored.riskLevel()).isEqualTo(governance.riskLevel());
    assertThat(restored.owner().length()).isEqualTo(governance.owner().length());
  }

  @Test
  @DisplayName("record_普通快照原样落库并解析回原治理")
  void ordinarySnapshotRoundTripsVerbatim() {
    AssetGovernance governance =
        new AssetGovernance(
            "alice",
            "v1",
            AssetGovernance.Visibility.WORKSPACE,
            "low",
            AssetGovernance.Health.ACTIVE,
            "team-a",
            null);
    String snapshot = AssetGovernanceStore.snapshotYaml(governance);

    recorder.record("admin", "agent", "agent-1", "v1", snapshot);

    List<AssetGovernanceRevision> rows = repository.findAll();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getSnapshotText()).isEqualTo(snapshot);
    AssetGovernance restored =
        AssetGovernanceStore.parseSnapshotYaml(rows.get(0).getSnapshotText());
    assertThat(restored).isEqualTo(governance);
  }
}
