package io.oryxos.web.controller.dto;

import io.oryxos.storage.AssetGovernanceRevision;
import java.time.Instant;

/** 资产治理版本快照视图（#537）。 */
public record AssetGovernanceRevisionView(
    long id, String versionLabel, String snapshotText, String actor, Instant createdAt) {

  public static AssetGovernanceRevisionView from(AssetGovernanceRevision row) {
    return new AssetGovernanceRevisionView(
        row.getId() == null ? 0L : row.getId(),
        row.getVersionLabel(),
        row.getSnapshotText(),
        row.getActor(),
        row.getCreatedAt());
  }
}
