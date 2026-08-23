package io.oryxos.storage;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** llm_pricing 的查询通道：成本计算按 (provider, model) 查价，管理台 CRUD 复用 JpaRepository 基础方法。 */
public interface LlmPricingRepository extends JpaRepository<LlmPricing, Long> {

  Optional<LlmPricing> findByProviderAndModel(String provider, String model);
}
