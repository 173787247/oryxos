package io.oryxos.storage;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** asset_governance_revisions 追加型仓储；业务层禁止 update/delete。 */
public interface AssetGovernanceRevisionRepository
    extends JpaRepository<AssetGovernanceRevision, Long> {

  List<AssetGovernanceRevision> findByResourceTypeAndResourceIdOrderByCreatedAtDescIdDesc(
      String resourceType, String resourceId);
}
