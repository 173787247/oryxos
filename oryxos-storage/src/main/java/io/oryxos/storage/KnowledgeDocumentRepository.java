package io.oryxos.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** knowledge_documents 读写通道（014 知识库）。 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocumentEntity, Long> {

  List<KnowledgeDocumentEntity> findByKbNameAndGeneration(String kbName, long generation);

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
