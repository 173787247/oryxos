package io.oryxos.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** teams 目录仓储。 */
public interface TeamRepository extends JpaRepository<Team, String> {

  List<Team> findAllByOrderByTeamIdAsc();

  Optional<Team> findByTeamId(String teamId);

  boolean existsByTeamId(String teamId);

  List<Team> findByOrgIdOrderByTeamIdAsc(String orgId);

  List<Team> findByParentTeamIdOrderByTeamIdAsc(String parentTeamId);
}
