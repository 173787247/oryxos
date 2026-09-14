package io.oryxos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** 已提交代次（027）：写路径被构建认领互斥（同一时刻至多一个提交者），save 即安全 upsert。 */
public interface KnowledgeGenerationRepository
    extends JpaRepository<KnowledgeGenerationEntity, String> {}
