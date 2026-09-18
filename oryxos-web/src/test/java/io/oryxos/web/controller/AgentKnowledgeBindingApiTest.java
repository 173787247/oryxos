package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.agent.AgentExecutionService;
import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.knowledge.KnowledgeBindingService;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.policy.Action;
import io.oryxos.core.policy.AuthorizationService;
import io.oryxos.core.policy.ResourceRef;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.SessionManager;
import io.oryxos.core.testing.SymlinkAssumptions;
import io.oryxos.web.GlobalExceptionHandler;
import io.oryxos.web.security.AssetBindGuard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** T035：Agent 知识库绑定三件套 + 整体替换 + 创建入口的 knowledgeBindings（FR-002/018/019）。 */
class AgentKnowledgeBindingApiTest {

  @TempDir Path root;

  private AgentLifecycleService lifecycle;
  private MockMvc mvc;

  @BeforeEach
  void setUp() throws Exception {
    Files.createDirectories(root.resolve("agents/ops"));
    Files.writeString(root.resolve("agents/ops/AGENT.md"), "---\nname: ops\n---\nbody");
    knowledgeBase("ops-manual", "运维手册");
    knowledgeBase("faq", "产品FAQ");
    KnowledgeBindingService bindings = new KnowledgeBindingService(root);
    Profile profile = profile("ops");
    ProfileRegistry profiles = new ProfileRegistry(Map.of("ops", profile));
    lifecycle = mock(AgentLifecycleService.class);
    when(lifecycle.create(eq("ops"), any(), any(), any(), any())).thenReturn(profile);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new AgentApiController(
                    lifecycle,
                    mock(AgentService.class),
                    mock(SessionManager.class),
                    profiles,
                    mock(MemoryService.class),
                    mock(AgentExecutionService.class),
                    null,
                    null,
                    bindings))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("bind → get 可见；unbind → 清空；replace 整体替换")
  void bindingCrudAndReplace() throws Exception {
    SymlinkAssumptions.assumeSymlinksSupported(root);
    mvc.perform(put("/api/v1/agents/ops/knowledge/ops-manual"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bindings[0].name").value("ops-manual"))
        .andExpect(jsonPath("$.data.bindings[0].description").value("运维手册"));

    mvc.perform(get("/api/v1/agents/ops/knowledge"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bindings.length()").value(1));

    mvc.perform(
            put("/api/v1/agents/ops/knowledge")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"knowledge\":[\"faq\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bindings[0].name").value("faq"));

    mvc.perform(delete("/api/v1/agents/ops/knowledge/faq"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bindings.length()").value(0));
  }

  @Test
  @DisplayName("绑定不存在的库 → 400；不存在的 Agent → 404")
  void errorsAreReadable() throws Exception {
    mvc.perform(put("/api/v1/agents/ops/knowledge/nope")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/agents/ghost/knowledge")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("创建 Agent 携带 knowledgeBindings → 绑定同步建立（FR-018 路径一）")
  void createWithKnowledgeBindings() throws Exception {
    SymlinkAssumptions.assumeSymlinksSupported(root);
    mvc.perform(
            post("/api/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"ops\",\"description\":\"x\",\"knowledgeBindings\":[\"ops-manual\"]}"))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/agents/ops/knowledge"))
        .andExpect(jsonPath("$.data.bindings[0].name").value("ops-manual"));
  }

  @Test
  @DisplayName("GOVERNANCE 列表不可见时拒绝绑定知识库")
  void bindRejectsWhenGovernanceHidesKnowledge() throws Exception {
    SymlinkAssumptions.assumeSymlinksSupported(root);
    AuthorizationService authorization = mock(AuthorizationService.class);
    when(authorization.decide(any(), eq(Action.MANAGE_KNOWLEDGE), any()))
        .thenReturn(AuthorizationService.Decision.ALLOWED);
    when(authorization.decide(any(), eq(Action.READ_WORKSPACE), eq(ResourceRef.knowledge("faq"))))
        .thenReturn(AuthorizationService.Decision.denied("私有资产仅属主或管理员可访问"));
    when(authorization.decide(
            any(), eq(Action.READ_WORKSPACE), eq(ResourceRef.knowledge("ops-manual"))))
        .thenReturn(AuthorizationService.Decision.ALLOWED);

    AgentApiController controller =
        new AgentApiController(
            lifecycle,
            mock(AgentService.class),
            mock(SessionManager.class),
            new ProfileRegistry(Map.of("ops", profile("ops"))),
            mock(MemoryService.class),
            mock(AgentExecutionService.class),
            null,
            null,
            new KnowledgeBindingService(root));
    controller.setAssetBindGuard(new AssetBindGuard(authorization));
    MockMvc gated =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    gated
        .perform(put("/api/v1/agents/ops/knowledge/faq"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Knowledge")));
  }

  private void knowledgeBase(String name, String description) throws Exception {
    Path dir = Files.createDirectories(root.resolve("knowledge").resolve(name));
    Files.writeString(
        dir.resolve("KNOWLEDGE.md"),
        "---\nname: " + name + "\ndescription: " + description + "\n---\n");
  }

  private static Profile profile(String name) {
    return new Profile(
        name,
        null,
        null,
        new Profile.ProviderRef("mock", "mock", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }
}
