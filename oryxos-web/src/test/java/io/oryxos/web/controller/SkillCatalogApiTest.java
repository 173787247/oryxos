package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.policy.ResourceRef;
import io.oryxos.core.skill.SkillCatalog;
import io.oryxos.core.skill.SkillCatalogEntry;
import io.oryxos.core.skill.SkillLoader;
import io.oryxos.core.skill.SkillRegistry;
import io.oryxos.core.skill.SkillService;
import io.oryxos.core.skill.SkillStore;
import io.oryxos.web.GlobalExceptionHandler;
import io.oryxos.web.security.AssetBindGuard;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SkillCatalogApiTest {

  @TempDir Path root;

  @Test
  void queriesPublicPrivateAndInstalledMetadata() throws Exception {
    SkillCatalog catalog =
        (query, visibility) ->
            List.of(
                    new SkillCatalogEntry(
                        "public-report", "报告", SkillCatalogEntry.Visibility.PUBLIC, "team", true),
                    new SkillCatalogEntry(
                        "private-ops",
                        "运维",
                        SkillCatalogEntry.Visibility.PRIVATE,
                        "personal",
                        false))
                .stream()
                .filter(entry -> visibility == null || entry.visibility() == visibility)
                .filter(entry -> query == null || query.isBlank() || entry.name().contains(query))
                .toList();
    MockMvc mvc = mvc(catalog, null);

    mvc.perform(get("/api/v1/skills/catalog").param("visibility", "public"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].name").value("public-report"))
        .andExpect(jsonPath("$.data[0].visibility").value("PUBLIC"))
        .andExpect(jsonPath("$.data[0].installed").value(true));
    mvc.perform(get("/api/v1/skills/catalog").param("visibility", "private").param("q", "ops"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].name").value("private-ops"))
        .andExpect(jsonPath("$.data[0].installed").value(false));
  }

  @Test
  void missingCatalogFailsClosedWith503() throws Exception {
    mvc(null, null)
        .perform(get("/api/v1/skills/catalog"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(503));
  }

  @Test
  void installedRowsHiddenWhenGovernanceDenies() throws Exception {
    SkillCatalog catalog =
        (query, visibility) ->
            List.of(
                new SkillCatalogEntry(
                    "secret-skill", "秘", SkillCatalogEntry.Visibility.PUBLIC, "local", true),
                new SkillCatalogEntry(
                    "ext-only", "外", SkillCatalogEntry.Visibility.PUBLIC, "remote", false));
    AssetBindGuard guard = mock(AssetBindGuard.class);
    when(guard.isVisible(any(), eq(ResourceRef.skill("secret-skill")))).thenReturn(false);
    when(guard.isVisible(any(), eq(ResourceRef.skill("ext-only")))).thenReturn(true);

    MockMvc mvc = mvc(catalog, guard);
    mvc.perform(get("/api/v1/skills/catalog"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].name").value("ext-only"));
  }

  private MockMvc mvc(SkillCatalog catalog, AssetBindGuard guard) {
    SkillLoader loader = new SkillLoader(root.resolve("skills"));
    SkillService service = new SkillService(new SkillStore(root), new SkillRegistry(), loader);
    SkillApiController controller = new SkillApiController(service, catalog, null);
    if (guard != null) {
      controller.setAssetBindGuard(guard);
    }
    return MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }
}
