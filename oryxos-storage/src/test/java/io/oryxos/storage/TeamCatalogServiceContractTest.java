package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** TeamCatalogService 契约：create/ensure/rename/list/delete/setOrg。 */
@org.springframework.transaction.annotation.Transactional
abstract class TeamCatalogServiceContractTest {

  @Autowired private TeamRepository repository;
  @Autowired private OrganizationRepository organizationRepository;

  private TeamCatalogService service() {
    return new TeamCatalogService(repository, organizationRepository);
  }

  private OrganizationCatalogService orgs() {
    return new OrganizationCatalogService(organizationRepository, repository);
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

  @Test
  @DisplayName("ensure_缺失则创建_已存在则跳过")
  void ensure_createsMissing_skipsExisting() {
    TeamCatalogService svc = service();
    Team first = svc.ensure("eng");
    assertEquals("eng", first.getTeamId());
    assertEquals("eng", first.getDisplayName());
    svc.ensure("eng", "ignored-rename");
    assertEquals(1, svc.list().size());
    assertEquals("eng", svc.find("eng").orElseThrow().getDisplayName());
  }

  @Test
  @DisplayName("setOrg_赋值与清空")
  void setOrg_assignsAndClears() {
    TeamCatalogService svc = service();
    orgs().create("acme", "Acme");
    svc.create("eng", "Engineering");

    Team assigned = svc.setOrg("eng", "acme");
    assertEquals("acme", assigned.getOrgId());
    assertEquals("acme", svc.find("eng").orElseThrow().getOrgId());

    Team cleared = svc.setOrg("eng", null);
    assertNull(cleared.getOrgId());
    assertNull(svc.find("eng").orElseThrow().getOrgId());
  }

  @Test
  @DisplayName("setOrg_组织不存在_抛IllegalArgumentException")
  void setOrg_missingOrg_throws() {
    TeamCatalogService svc = service();
    svc.create("eng", "Engineering");
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> svc.setOrg("eng", "ghost"));
    assertTrue(ex.getMessage().contains("not found"));
  }
}
