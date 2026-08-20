package io.oryxos.storage;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * memory_entries 读写：核心区全量、归档区取最近 N（LIMIT）、归档区关键词 LIKE 检索。 全部查询带 agent 维度（015 FR-014）：任一 Agent
 * 的读写绝不触及其他 Agent 的条目。
 */
public interface MemoryEntryRepository extends JpaRepository<MemoryEntry, Long> {

  /** 核心区：全量、按写入顺序（永不截断——契约二）。 */
  List<MemoryEntry> findByAgentNameAndScopeOrderByIdAsc(String agentName, String scope);

  /** 归档区：最近 N 条（id 降序 + Pageable 限量，即 LIMIT——截断只作用归档）。 */
  List<MemoryEntry> findByAgentNameAndScopeOrderByIdDesc(
      String agentName, String scope, Pageable pageable);

  /** 归档区关键词检索（LIKE，契约四；SQLite LIKE 对 ASCII 天然不区分大小写——FR-002 由调用方统一保证）。 */
  @Query(
      "SELECT m FROM MemoryEntry m WHERE m.agentName = :agentName AND m.scope = 'ARCHIVAL'"
          + " AND m.content LIKE :pattern ORDER BY m.id ASC")
  List<MemoryEntry> searchArchival(
      @Param("agentName") String agentName, @Param("pattern") String pattern);
}
