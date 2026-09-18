package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** TeamCatalogService 契约：create/rename/list/delete。 */
@org.springframework.transaction.annotation.Transactional
abstract class TeamCatalogServiceContractTest {

  @Autowired private TeamRepository repository;

  private TeamCatalogService service() {
    return new TeamCatalogService(repository);
  }

  @Test
  @DisplayName("create_rename_list_delete")
  void createRenameListDelete() {
    TeamCatalogService svc = service();
    Team created = svc.create("eng", "Engineering");
    assertEquals("eng", created.getTeamId());
    assertEquals("Engineering", created.getDisplayName());

    svc.rename("eng", "Eng Platform");
    assertEquals(1, svc.list().size());
    assertEquals("Eng Platform", svc.find("eng").orElseThrow().getDisplayName());

    svc.delete("eng");
    svc.delete("eng");
    assertTrue(svc.list().isEmpty());
  }

  @Test
  @DisplayName("create_重名_抛IllegalArgumentException")
  void create_duplicate_throws() {
    TeamCatalogService svc = service();
    svc.create("eng", null);
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> svc.create("eng", "again"));
    assertTrue(ex.getMessage().contains("already exists"));
  }

  @Test
  @DisplayName("create_缺displayName_回落为teamId")
  void create_blankDisplay_fallsBackToId() {
    Team t = service().create("platform", "  ");
    assertEquals("platform", t.getDisplayName());
  }
}
