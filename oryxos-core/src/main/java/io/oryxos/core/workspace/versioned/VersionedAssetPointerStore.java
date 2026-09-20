package io.oryxos.core.workspace.versioned;

import java.util.List;
import java.util.OptionalLong;

/**
 * Shared authority for content versions (#473): catalog + active/previous pointers. File bytes stay
 * on the workspace volume under {@code .asset-versions/}.
 */
public interface VersionedAssetPointerStore {

  long nextVersion(VersionedAssetKind kind, String assetId);

  void recordVersion(
      VersionedAssetKind kind, String assetId, long version, String contentHash, String actor);

  boolean hasVersion(VersionedAssetKind kind, String assetId, long version);

  List<Long> listVersions(VersionedAssetKind kind, String assetId);

  OptionalLong activeVersion(VersionedAssetKind kind, String assetId);

  OptionalLong previousVersion(VersionedAssetKind kind, String assetId);

  /**
   * Atomically set active to {@code targetVersion}; previous becomes the old active. Returns false
   * if the target version was never published.
   */
  boolean activate(VersionedAssetKind kind, String assetId, long targetVersion, String actor);
}
