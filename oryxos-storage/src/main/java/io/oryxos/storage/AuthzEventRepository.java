package io.oryxos.storage;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** authz_events 追加写 + 按主体/动作/时间查询；故意不提供 delete*。 */
public interface AuthzEventRepository extends JpaRepository<AuthzEvent, Long> {

  List<AuthzEvent> findByPrincipalIdOrderByCreatedAtDesc(String principalId);

  List<AuthzEvent> findByActionOrderByCreatedAtDesc(String action);

  List<AuthzEvent> findAllByOrderByCreatedAtDesc();
}
