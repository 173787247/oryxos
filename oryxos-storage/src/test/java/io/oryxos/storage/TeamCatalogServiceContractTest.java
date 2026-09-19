package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** TeamCatalogService 契约：create/ensure/rename/list/delete/setOrg/setParent。 */
@org.springframework.transaction.annotation.Transactional
abstract class TeamCatalogServiceContractTest {

  @Autowired private TeamRepository repository;
  @Autowired private OrganizationRepository organizationRepository;

  private TeamCatalogService service() {
    return new TeamCatalogService(repository, organizationRepository);
  }

  private TeamCatalogService service(int maxTeamAncestorDepth) {
    return new TeamCatalogService(repository, organizationRepository, maxTeamAncestorDepth);
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

  @Test
  @DisplayName("setParent_赋值_清空_拒自身_拒缺失父")
  void setParent_assignClearRejectSelfMissing() {
    TeamCatalogService svc = service();
    svc.create("platform", "Platform");
    svc.create("eng", "Engineering");

    Team linked = svc.setParent("eng", "platform");
    assertEquals("platform", linked.getParentTeamId());

    Team cleared = svc.setParent("eng", null);
    assertNull(cleared.getParentTeamId());

    IllegalArgumentException self =
        assertThrows(IllegalArgumentException.class, () -> svc.setParent("eng", "eng"));
    assertTrue(self.getMessage().contains("own parent"));

    IllegalArgumentException missing =
        assertThrows(IllegalArgumentException.class, () -> svc.setParent("eng", "ghost"));
    assertTrue(missing.getMessage().contains("not found"));
  }

  @Test
  @DisplayName("setParent_拒A到B到A环")
  void setParent_rejectsTwoNodeCycle() {
    TeamCatalogService svc = service();
    svc.create("a", "A");
    svc.create("b", "B");
    svc.setParent("a", "b");

    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> svc.setParent("b", "a"));
    assertTrue(ex.getMessage().contains("cycle"));
    assertNull(svc.find("b").orElseThrow().getParentTeamId());
  }

  @Test
  @DisplayName("setParent_拒更深环")
  void setParent_rejectsDeeperCycle() {
    TeamCatalogService svc = service();
    svc.create("a", "A");
    svc.create("b", "B");
    svc.create("c", "C");
    svc.setParent("a", "b");
    svc.setParent("b", "c");

    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> svc.setParent("c", "a"));
    assertTrue(ex.getMessage().contains("cycle"));
    assertNull(svc.find("c").orElseThrow().getParentTeamId());
  }

  @Test
  @DisplayName("setParent_自定义深度过浅则放过更深环")
  void setParent_customDepthMissesDeeperCycle() {
    TeamCatalogService svc = service(1);
    svc.create("a", "A");
    svc.create("b", "B");
    svc.create("c", "C");
    svc.setParent("a", "b");
    svc.setParent("b", "c");
    Team linked = svc.setParent("c", "a");
    assertEquals("a", linked.getParentTeamId());
  }

  @Test
  @DisplayName("setParent_自定义深度足够则仍拒环")
  void setParent_customDepthStillRejectsWhenDeepEnough() {
    TeamCatalogService svc = service(2);
    svc.create("a", "A");
    svc.create("b", "B");
    svc.create("c", "C");
    svc.setParent("a", "b");
    svc.setParent("b", "c");
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> svc.setParent("c", "a"));
    assertTrue(ex.getMessage().contains("cycle"));
    assertNull(svc.find("c").orElseThrow().getParentTeamId());
  }

  @Test
  @DisplayName("setParent_合法链")
  void setParent_allowsValidChain() {
    TeamCatalogService svc = service();
    svc.create("root", "Root");
    svc.create("mid", "Mid");
    svc.create("leaf", "Leaf");
    svc.setParent("mid", "root");
    Team linked = svc.setParent("leaf", "mid");
    assertEquals("mid", linked.getParentTeamId());
    assertEquals("root", svc.find("mid").orElseThrow().getParentTeamId());
  }

  @Test
  @DisplayName("delete_清空子团队parent_team_id")
  void delete_clearsChildParentTeamId() {
    TeamCatalogService svc = service();
    svc.create("platform", "Platform");
    svc.create("eng", "Engineering");
    svc.setParent("eng", "platform");
    assertEquals("platform", svc.find("eng").orElseThrow().getParentTeamId());

    svc.delete("platform");
    assertNull(svc.find("eng").orElseThrow().getParentTeamId());
    assertTrue(svc.find("platform").isEmpty());
  }
}
