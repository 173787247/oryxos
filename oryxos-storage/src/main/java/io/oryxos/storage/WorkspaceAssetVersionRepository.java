package io.oryxos.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface WorkspaceAssetVersionRepository
    extends JpaRepository<WorkspaceAssetVersionEntity, Long> {

  boolean existsByKindAndAssetIdAndVersion(String kind, String assetId, long version);

  List<WorkspaceAssetVersionEntity> findByKindAndAssetIdOrderByVersionDesc(
      String kind, String assetId);

  @Query(
      "SELECT COALESCE(MAX(v.version), 0) FROM WorkspaceAssetVersionEntity v"
          + " WHERE v.kind = :kind AND v.assetId = :assetId")
  long maxVersion(String kind, String assetId);

  Optional<WorkspaceAssetVersionEntity> findByKindAndAssetIdAndVersion(
      String kind, String assetId, long version);
}
