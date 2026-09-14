package io.oryxos.storage;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** 工作区版本号总线（027）：DB 侧原子自增，杜绝读-改-写竞争；全量读走 findAll（表恒 4 行）。 */
public interface WorkspaceVersionRepository extends JpaRepository<WorkspaceVersionEntity, String> {

  @Transactional(rollbackFor = Exception.class)
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE WorkspaceVersionEntity w SET w.version = w.version + 1,"
          + " w.updatedBy = :owner, w.updatedAt = :now WHERE w.domain = :domain")
  int bump(String domain, String owner, Instant now);
}
