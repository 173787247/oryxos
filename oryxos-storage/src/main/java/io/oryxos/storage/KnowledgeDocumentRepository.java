package io.oryxos.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** knowledge_documents 读写通道（014 知识库）。 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, Long> {

  /**
   * 顺序是输出契约的一部分（检索同分排序、{@code /knowledge/{kb}/status} 都透传它），故显式 ORDER BY： 靠扫描顺序在 SQLite 上是偶然的，在
   * PostgreSQL 的并行计划下更不保证。
   */
  List<KnowledgeDocumentEntity> findByKbNameAndGenerationOrderByRelPathAsc(
      String kbName, long generation);

  Optional<KnowledgeDocumentEntity> findByKbNameAndRelPathAndGeneration(
      String kbName, String relPath, long generation);

  List<KnowledgeDocumentEntity> findByKbName(String kbName);

  @Transactional(rollbackFor = Exception.class)
  void deleteByKbName(String kbName);

  @Transactional(rollbackFor = Exception.class)
  void deleteByKbNameAndGenerationLessThan(String kbName, long generation);

  @Transactional(rollbackFor = Exception.class)
  void deleteByKbNameAndGeneration(String kbName, long generation);
}
