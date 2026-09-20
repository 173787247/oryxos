package io.oryxos.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** organizations 目录仓储。 */
public interface OrganizationRepository extends JpaRepository<Organization, String> {

  List<Organization> findAllByOrderByOrgIdAsc();

  Optional<Organization> findByOrgId(String orgId);

  boolean existsByOrgId(String orgId);

  List<Organization> findByParentOrgIdOrderByOrgIdAsc(String parentOrgId);
}
