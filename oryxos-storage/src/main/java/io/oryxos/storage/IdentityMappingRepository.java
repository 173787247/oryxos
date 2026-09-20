package io.oryxos.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** identity_mappings 访问通道；按 issuer+subject 查用于 OIDC 回调映射。 */
public interface IdentityMappingRepository extends JpaRepository<IdentityMapping, Long> {

  Optional<IdentityMapping> findByIssuerAndSubject(String issuer, String subject);

  List<IdentityMapping> findAllByOrderByIssuerAscSubjectAsc();

  void deleteByIssuerAndSubject(String issuer, String subject);
}
