package io.oryxos.storage;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** api_keys 表访问（018-rest-api-key）。校验按 key_hash 查（UNIQUE 隐式索引）。 */
public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

  Optional<ApiKey> findByKeyHash(String keyHash);

  Optional<ApiKey> findByName(String name);

  boolean existsByName(String name);
}
