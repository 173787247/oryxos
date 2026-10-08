package io.oryxos.storage;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 资产治理全文快照落库（#537）。写失败只记 ERROR，不回滚侧车——权威仍是 GOVERNANCE.yml / channels.yaml。 */
public class AssetGovernanceRevisionRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(AssetGovernanceRevisionRecorder.class);

  private static final int MAX_ACTOR = 128;
  private static final int MAX_ID = 255;
  private static final int MAX_TYPE = 32;
  private static final int MAX_VERSION = 128;

  /**
   * 与治理文本在别处的读写上限（{@code AssetGovernanceStore.MAX_GOVERNANCE_FILE_BYTES}，10 MiB）对齐：这一列不是旁路日志，而是
   * {@code AssetGovernanceApiSupport.restore} 的权威内容源，截断又是静默的——截出来的残缺 YAML 仍然可解析（owner 变短、其后
   * version/visibility/riskLevel/health 全空），restore 会照它写回现网。{@code @Lob} 列本身没有宽度约束，原先的 64 KiB
   * 只由这一行决定。
   */
  private static final int MAX_SNAPSHOT = 10 * 1024 * 1024;

  private static final String UNKNOWN_ACTOR = "anonymous";

  private final AssetGovernanceRevisionRepository repository;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository 为 Spring 注入共享单例，存同一引用正是意图。")
  public AssetGovernanceRevisionRecorder(AssetGovernanceRevisionRepository repository) {
    this.repository = repository;
  }

  /** 追加一次全文快照；失败吞掉并记 ERROR。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "exception toString 仅诊断；快照写失败不得回滚已落盘的侧车。")
  public void record(
      String actor,
      String resourceType,
      String resourceId,
      String versionLabel,
      String snapshotText) {
    try {
      AssetGovernanceRevision row = new AssetGovernanceRevision();
      row.setActor(truncate(actor == null || actor.isBlank() ? UNKNOWN_ACTOR : actor, MAX_ACTOR));
      row.setResourceType(truncate(resourceType == null ? "unknown" : resourceType, MAX_TYPE));
      row.setResourceId(truncate(resourceId == null ? "" : resourceId, MAX_ID));
      row.setVersionLabel(
          versionLabel == null || versionLabel.isBlank()
              ? null
              : truncate(versionLabel.strip(), MAX_VERSION));
      String snap = snapshotText == null ? "" : snapshotText;
      row.setSnapshotText(truncate(snap, MAX_SNAPSHOT));
      repository.save(row);
    } catch (RuntimeException ex) {
      LOG.error("asset_governance_revisions 写入失败（侧车不回滚）：{}", ex.toString());
    }
  }

  /** 按资源列出快照（新→旧）；无记录返回空列表。 */
  public List<AssetGovernanceRevision> list(String resourceType, String resourceId) {
    if (resourceType == null
        || resourceId == null
        || resourceType.isBlank()
        || resourceId.isBlank()) {
      return List.of();
    }
    return repository.findByResourceTypeAndResourceIdOrderByCreatedAtDescIdDesc(
        resourceType.strip(), resourceId.strip());
  }

  /** 按主键查快照；不存在返回 empty。 */
  public Optional<AssetGovernanceRevision> find(long id) {
    if (id <= 0) {
      return Optional.empty();
    }
    return repository.findById(id);
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
