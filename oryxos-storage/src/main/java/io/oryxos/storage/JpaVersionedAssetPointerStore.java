package io.oryxos.storage;

import io.oryxos.core.workspace.versioned.VersionedAssetKind;
import io.oryxos.core.workspace.versioned.VersionedAssetPointerStore;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.transaction.annotation.Transactional;

/** JPA-backed shared pointer store for versioned assets (#473). */
public class JpaVersionedAssetPointerStore implements VersionedAssetPointerStore {

  private final WorkspaceAssetVersionRepository versions;
  private final WorkspaceAssetActiveRepository active;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring repositories are shared singletons by design.")
  public JpaVersionedAssetPointerStore(
      WorkspaceAssetVersionRepository versions, WorkspaceAssetActiveRepository active) {
    this.versions = versions;
    this.active = active;
  }

  @Override
  @Transactional(readOnly = true)
  public long nextVersion(VersionedAssetKind kind, String assetId) {
    return versions.maxVersion(kind.directory(), assetId) + 1;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void recordVersion(
      VersionedAssetKind kind, String assetId, long version, String contentHash, String actor) {
    WorkspaceAssetVersionEntity row = new WorkspaceAssetVersionEntity();
    row.setKind(kind.directory());
    row.setAssetId(assetId);
    row.setVersion(version);
    row.setContentHash(contentHash == null ? "" : contentHash);
    row.setCreatedBy(actor == null || actor.isBlank() ? "anonymous" : actor);
    row.setCreatedAt(Instant.now());
    versions.save(row);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean hasVersion(VersionedAssetKind kind, String assetId, long version) {
    return versions.existsByKindAndAssetIdAndVersion(kind.directory(), assetId, version);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Long> listVersions(VersionedAssetKind kind, String assetId) {
    return versions.findByKindAndAssetIdOrderByVersionDesc(kind.directory(), assetId).stream()
        .map(WorkspaceAssetVersionEntity::getVersion)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public OptionalLong activeVersion(VersionedAssetKind kind, String assetId) {
    return active
        .findById(new WorkspaceAssetActiveEntity.Pk(kind.directory(), assetId))
        .map(row -> OptionalLong.of(row.getActiveVersion()))
        .orElseGet(OptionalLong::empty);
  }

  @Override
  @Transactional(readOnly = true)
  public OptionalLong previousVersion(VersionedAssetKind kind, String assetId) {
    return active
        .findById(new WorkspaceAssetActiveEntity.Pk(kind.directory(), assetId))
        .map(WorkspaceAssetActiveEntity::getPreviousVersion)
        .filter(prev -> prev != null)
        .map(OptionalLong::of)
        .orElseGet(OptionalLong::empty);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public boolean activate(
      VersionedAssetKind kind, String assetId, long targetVersion, String actor) {
    if (!hasVersion(kind, assetId, targetVersion)) {
      return false;
    }
    WorkspaceAssetActiveEntity.Pk pk = new WorkspaceAssetActiveEntity.Pk(kind.directory(), assetId);
    WorkspaceAssetActiveEntity row = active.findById(pk).orElse(null);
    Long previous = null;
    if (row == null) {
      row = new WorkspaceAssetActiveEntity();
      row.setKind(kind.directory());
      row.setAssetId(assetId);
    } else if (row.getActiveVersion() == targetVersion) {
      return true;
    } else {
      previous = row.getActiveVersion();
    }
    row.setActiveVersion(targetVersion);
    row.setPreviousVersion(previous);
    row.setUpdatedBy(actor == null || actor.isBlank() ? "anonymous" : actor);
    row.setUpdatedAt(Instant.now());
    active.save(row);
    return true;
  }
}
