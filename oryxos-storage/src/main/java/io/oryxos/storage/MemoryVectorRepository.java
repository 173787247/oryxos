package io.oryxos.storage;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** memory_vectors 读写通道（015 记忆检索升级）——语义路按 agent 全量加载做内存余弦扫描（沿用 014 D1 路线）。 */
public interface MemoryVectorRepository extends JpaRepository<MemoryVectorEntity, Long> {

  List<MemoryVectorEntity> findByAgentName(String agentName);

  Optional<MemoryVectorEntity> findByAgentNameAndEntryHash(String agentName, String entryHash);

  @Transactional(rollbackFor = Exception.class)
  void deleteByAgentNameAndEntryHashIn(String agentName, Collection<String> entryHashes);

  /** 模型变更整体重建（FR-007）：清掉所有非当前模型的行，随对账重新入队。 */
  @Transactional(rollbackFor = Exception.class)
  void deleteByEmbeddingModelNot(String embeddingModel);
}
