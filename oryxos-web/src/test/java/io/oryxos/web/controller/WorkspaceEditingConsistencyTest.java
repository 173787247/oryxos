package io.oryxos.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.*;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.skill.*;
import io.oryxos.core.workspace.*;
import io.oryxos.web.GlobalExceptionHandler;
import io.oryxos.web.workspace.WorkspacePublicationFilter;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkspaceEditingConsistencyTest {
  @TempDir Path root;

  @Test
  void laggingReplicaReturnsCanonicalAgentAndSkillWithCurrentRevision() throws Exception {
    WorkspaceStorage storage = new LocalWorkspaceStorageProvider().open(root, null);
    AgentStore agentStore = new AgentStore(storage.root());
    agentStore.write("demo", markdown("old"));
    AgentLoader loader = new AgentLoader(storage.root().resolve("agents"), Set.of("mock"));
    ProfileRegistry staleProfiles = loader.loadAll();
    AgentLifecycleService lifecycle =
        new AgentLifecycleService(
            loader,
            staleProfiles,
            mock(AgentScheduler.class),
            agentStore,
            mock(io.oryxos.core.provider.ProviderService.class),
            "mock",
            "mock",
            "model",
            Map.of(),
            mock(io.oryxos.core.notify.NotifyChannelRegistry.class));
    SkillStore skillStore = new SkillStore(storage.root());
    skillStore.write("report", "---\nname: report\ndescription: old skill\n---\nold body");
    SkillLoader skillLoader = new SkillLoader(storage.root().resolve("skills"));
    SkillRegistry staleSkills = skillLoader.loadAll();
    SkillService skillService = new SkillService(skillStore, staleSkills, skillLoader);
    WorkspacePublication publications = new WorkspacePublication(storage.root());
    var writer = publications.begin(null, "other replica update");
    agentStore.write("demo", markdown("new"));
    skillStore.write("report", "---\nname: report\ndescription: new skill\n---\nnew body");
    String latest = writer.commit();
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                controller(lifecycle, staleProfiles, storage),
                new SkillApiController(
                    skillService, null, new AgentSkillBindingService(storage.root(), skillLoader)))
            .addFilters(new WorkspacePublicationFilter(storage, new ObjectMapper()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    mvc.perform(get("/api/v1/agents/demo"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Workspace-Revision", latest))
        .andExpect(jsonPath("$.data.description").value("new"));
    mvc.perform(get("/api/v1/agents")).andExpect(jsonPath("$.data[0].description").value("new"));
    mvc.perform(get("/api/v1/skills/report"))
        .andExpect(header().string("X-Workspace-Revision", latest))
        .andExpect(jsonPath("$.data.description").value("new skill"));
    mvc.perform(get("/api/v1/skills"))
        .andExpect(jsonPath("$.data[0].description").value("new skill"));
    assertEquals("old", staleProfiles.get("demo").orElseThrow().description());
    assertEquals("old skill", staleSkills.get("report").orElseThrow().description());
  }

  @Test
  void invalidKnowledgeTargetsDoNotRunFileWriterAndRejectedRevisionIsInvalidated()
      throws Exception {
    WorkspaceStorage storage = new LocalWorkspaceStorageProvider().open(root, null);
    AgentStore store = new AgentStore(storage.root());
    store.write("demo", markdown("old"));
    AgentLifecycleService lifecycle = mock(AgentLifecycleService.class);
    doAnswer(
            invocation -> {
              store.write("demo", markdown("changed"));
              return null;
            })
        .when(lifecycle)
        .saveFiles(any(), any(), any());
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(controller(lifecycle, new ProfileRegistry(), storage))
            .addFilters(new WorkspacePublicationFilter(storage, new ObjectMapper()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String before = new WorkspacePublication(storage.root()).revision();
    String request =
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "files",
                    Map.of("AGENT.md", markdown("changed")),
                    "knowledgeBindings",
                    List.of("missing")));
    var result =
        mvc.perform(
                post("/api/v1/agents/demo/files")
                    .header("If-Match", before)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("X-Workspace-Outcome", "rejected-unverified"))
            .andReturn();
    verify(lifecycle, never()).saveFiles(any(), any(), any());
    assertEquals(markdown("old"), store.read("demo"));
    assertNotEquals(before, result.getResponse().getHeader("X-Workspace-Revision"));
    assertFalse(Files.exists(root.resolve(WorkspacePublication.RESERVATION)));
    try (var files = Files.list(root)) {
      assertTrue(
          files.anyMatch(path -> path.getFileName().toString().startsWith(".workspace-rejected-")));
    }
    mvc.perform(
            post("/api/v1/agents/demo/files")
                .header("If-Match", before)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isConflict());
  }

  private static AgentApiController controller(
      AgentLifecycleService lifecycle, ProfileRegistry profiles, WorkspaceStorage storage) {
    return new AgentApiController(
        lifecycle,
        mock(AgentService.class),
        mock(io.oryxos.core.session.SessionManager.class),
        profiles,
        mock(io.oryxos.core.memory.MemoryService.class),
        mock(AgentExecutionService.class),
        null,
        null,
        new io.oryxos.core.knowledge.KnowledgeBindingService(storage.root()));
  }

  private static String markdown(String description) {
    return "---\nname: demo\ndescription: "
        + description
        + "\nprovider:\n  name: mock\n  model: model\n---\nbody";
  }
}
