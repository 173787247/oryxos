package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** OrganizationCatalogService 契约：create/ensure/rename/list/delete/setParent。 */
@org.springframework.transaction.annotation.Transactional
abstract class OrganizationCatalogServiceContractTest {

  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private TeamRepository teamRepository;

  private OrganizationCatalogService orgs() {
    return new OrganizationCatalogService(organizationRepository, teamRepository);
  }

  private TeamCatalogService teams() {
    return new TeamCatalogService(teamRepository, organizationRepository);
  }

  @Test
  @DisplayName("create_rename_list_delete")
  void createRenameListDelete() {
    OrganizationCatalogService svc = orgs();
    Organization created = svc.create("acme", "Acme Corp");
    assertEquals("acme", created.getOrgId());
    assertEquals("Acme Corp", created.getDisplayName());

    svc.rename("acme", "Acme Inc");
    assertEquals(1, svc.list().size());
    assertEquals("Acme Inc", svc.find("acme").orElseThrow().getDisplayName());

    svc.delete("acme");
    svc.delete("acme");
    assertTrue(svc.list().isEmpty());
  }

  @Test
  @DisplayName("create_重名_抛IllegalArgumentException")
  void create_duplicate_throws() {
    OrganizationCatalogService svc = orgs();
    svc.create("acme", null);
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> svc.create("acme", "again"));
    assertTrue(ex.getMessage().contains("already exists"));
  }

  @Test
  @DisplayName("ensure_缺失则创建_已存在则跳过")
  void ensure_createsMissing_skipsExisting() {
    OrganizationCatalogService svc = orgs();
    Organization first = svc.ensure("acme");
    assertEquals("acme", first.getOrgId());
    assertEquals("acme", first.getDisplayName());
    svc.ensure("acme", "ignored-rename");
    assertEquals(1, svc.list().size());
    assertEquals("acme", svc.find("acme").orElseThrow().getDisplayName());
  }

  @Test
  @DisplayName("delete_清空引用该org的teams.org_id")
  void delete_clearsTeamOrgId() {
    OrganizationCatalogService orgSvc = orgs();
    TeamCatalogService teamSvc = teams();
    orgSvc.create("acme", "Acme");
    teamSvc.create("eng", "Engineering");
    teamSvc.setOrg("eng", "acme");
    assertEquals("acme", teamSvc.find("eng").orElseThrow().getOrgId());

    orgSvc.delete("acme");
    assertNull(teamSvc.find("eng").orElseThrow().getOrgId());
    assertTrue(orgSvc.list().isEmpty());
  }

  @Test
  @DisplayName("setParent_赋值_清空_拒自身_拒缺失父")
  void setParent_assignClearRejectSelfMissing() {
    OrganizationCatalogService svc = orgs();
    svc.create("acme", "Acme");
    svc.create("eng", "Engineering");

    Organization linked = svc.setParent("eng", "acme");
    assertEquals("acme", linked.getParentOrgId());

    Organization cleared = svc.setParent("eng", null);
    assertNull(cleared.getParentOrgId());

    IllegalArgumentException self =
        assertThrows(IllegalArgumentException.class, () -> svc.setParent("eng", "eng"));
    assertTrue(self.getMessage().contains("own parent"));

    IllegalArgumentException missing =
        assertThrows(IllegalArgumentException.class, () -> svc.setParent("eng", "ghost"));
    assertTrue(missing.getMessage().contains("not found"));
  }

  @Test
  @DisplayName("delete_清空子组织parent_org_id")
  void delete_clearsChildParentOrgId() {
    OrganizationCatalogService svc = orgs();
    svc.create("acme", "Acme");
    svc.create("eng", "Engineering");
    svc.setParent("eng", "acme");
    assertEquals("acme", svc.find("eng").orElseThrow().getParentOrgId());

    svc.delete("acme");
    assertNull(svc.find("eng").orElseThrow().getParentOrgId());
    assertTrue(svc.find("acme").isEmpty());
  }
}
