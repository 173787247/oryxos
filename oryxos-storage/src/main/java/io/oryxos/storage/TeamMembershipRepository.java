package io.oryxos.storage;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** team_memberships 访问通道。 */
public interface TeamMembershipRepository extends JpaRepository<TeamMembership, Long> {

  List<TeamMembership> findByUsernameOrderByTeamIdAsc(String username);

  Optional<TeamMembership> findByUsernameAndTeamId(String username, String teamId);

  boolean existsByUsernameAndTeamId(String username, String teamId);

  void deleteByUsernameAndTeamId(String username, String teamId);
}
