package io.oryxos.storage;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** knowledge_chunks 读写通道（014 知识库）——检索按库 + 代号全量加载做内存余弦扫描（research D1）。 */
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunkEntity, Long> {

  /** 片段按 id 升序，即保留落库次序——内存档同样如此排序，检索在得分相同时按它决定谁进 top-K。 显式 ORDER BY 让这条语义不再依赖扫描顺序。 */
  List<KnowledgeChunkEntity> findByKbNameAndGenerationOrderByIdAsc(String kbName, long generation);

  long countByKbNameAndGeneration(String kbName, long generation);

  @Transactional(rollbackFor = Exception.class)
  void deleteByDocumentId(long documentId);

  @Transactional(rollbackFor = Exception.class)
  void deleteByKbName(String kbName);

  @Transactional(rollbackFor = Exception.class)
  void deleteByKbNameAndGenerationLessThan(String kbName, long generation);

  @Transactional(rollbackFor = Exception.class)
  void deleteByKbNameAndGeneration(String kbName, long generation);
}
