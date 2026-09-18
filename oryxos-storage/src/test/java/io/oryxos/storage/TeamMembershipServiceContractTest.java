package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** TeamMembershipService 契约：add/remove/list 幂等与用户存在性。 */
@org.springframework.transaction.annotation.Transactional
abstract class TeamMembershipServiceContractTest {

  @Autowired private TeamMembershipRepository membershipRepository;
  @Autowired private WebUserRepository userRepository;

  private final PasswordEncoder encoder =
      PasswordEncoderFactories.createDelegatingPasswordEncoder();

  private TeamMembershipService service() {
    return new TeamMembershipService(membershipRepository, userRepository);
  }

  private void ensureUser(String username) {
    new WebUserService(userRepository, encoder).create(username, "password1");
  }

  @Test
  @DisplayName("add_list_remove_幂等")
  void addListRemove_idempotent() {
    ensureUser("alice");
    TeamMembershipService svc = service();

    svc.add("alice", "eng");
    svc.add("alice", "eng");
    svc.add("alice", "platform");
    assertEquals(Set.of("eng", "platform"), svc.listTeamIds("alice"));

    svc.remove("alice", "eng");
    svc.remove("alice", "eng");
    assertEquals(Set.of("platform"), svc.listTeamIds("alice"));
  }

  @Test
  @DisplayName("add_用户不存在_抛IllegalArgumentException")
  void add_unknownUser_throws() {
    TeamMembershipService svc = service();
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> svc.add("nobody", "eng"));
    assertTrue(ex.getMessage().contains("not found"));
  }

  @Test
  @DisplayName("list_未知用户_空集")
  void list_unknownUser_empty() {
    assertTrue(service().listTeamIds("ghost").isEmpty());
  }
}
