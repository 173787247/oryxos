package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.OryxTool;
import io.oryxos.core.policy.ToolPolicyService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.storage.ToolPolicyRule;
import io.oryxos.storage.ToolPolicyRuleRepository;
import io.oryxos.storage.WebSessionService;
import io.oryxos.web.GlobalExceptionHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 020 验收 harness：PolicyApiControllerTest——治理端点契约钉死（contracts/policy-api.md §3）。守：CRUD、 GLOBAL_DENY
 * 带 agentName → 400、EXEMPT/AGENT_DENY 缺 agentName → 400、重复 409、删除不存在 404、 未知工具名保存成功且 unknownTarget
 * 标记、effective 视图 declared/effective/removed 与命中原因。
 */
class PolicyApiControllerTest {

  private ToolPolicyRuleRepository repository;
  private MockMvc mvc;
  private final List<ToolPolicyRule> rules = new ArrayList<>();

  @BeforeEach
  void setUp() {
    repository = mock(ToolPolicyRuleRepository.class);
    when(repository.findAll()).thenReturn(rules);
    when(repository.save(any(ToolPolicyRule.class)))
        .thenAnswer(
            inv -> {
              ToolPolicyRule r = inv.getArgument(0);
              ReflectionTestUtils.setField(r, "id", 1L);
              rules.add(r);
              return r;
            });
    ProfileRegistry profileRegistry = new ProfileRegistry();
    profileRegistry.register(profile("kb-tester", List.of("shell", "read_file")));
    // 策略语义用真实收敛逻辑口径的 stub：deny shell（模拟已存 GLOBAL_DENY）
    ToolPolicyService policy =
        (agent, tool) ->
            "shell".equals(tool)
                ? ToolPolicyService.PolicyDecision.denied("命中全局禁用规则（shell）")
                : ToolPolicyService.PolicyDecision.ALLOWED;
    OryxTool shell = mock(OryxTool.class);
    PolicyApiController controller =
        new PolicyApiController(
            repository,
            policy,
            profileRegistry,
            Map.of("shell", shell),
            mock(WebSessionService.class));
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("POST_GLOBAL_DENY_成功且createdBy为anonymous（无session）")
  void createGlobalDeny_ok() throws Exception {
    mvc.perform(
            post("/api/v1/tool-policy/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ruleType\":\"GLOBAL_DENY\",\"pattern\":\"shell\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.ruleType").value("GLOBAL_DENY"))
        .andExpect(jsonPath("$.data.createdBy").value("anonymous"))
        .andExpect(jsonPath("$.data.unknownTarget").value(false));
  }

  @Test
  @DisplayName("POST_GLOBAL_DENY带agentName_400；EXEMPT缺agentName_400；ruleType非法_400")
  void createValidation_400() throws Exception {
    mvc.perform(
            post("/api/v1/tool-policy/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ruleType\":\"GLOBAL_DENY\",\"agentName\":\"a\",\"pattern\":\"x\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/tool-policy/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ruleType\":\"AGENT_EXEMPT\",\"pattern\":\"x\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/tool-policy/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ruleType\":\"NOPE\",\"pattern\":\"x\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("POST_重复规则_409")
  void duplicateRule_409() throws Exception {
    when(repository.existsByRuleTypeAndAgentNameAndPattern("GLOBAL_DENY", null, "shell"))
        .thenReturn(true);

    mvc.perform(
            post("/api/v1/tool-policy/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ruleType\":\"GLOBAL_DENY\",\"pattern\":\"shell\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("POST_未知工具名_保存成功且unknownTarget=true")
  void unknownTool_savedWithWarningFlag() throws Exception {
    mvc.perform(
            post("/api/v1/tool-policy/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ruleType\":\"GLOBAL_DENY\",\"pattern\":\"no_such_tool\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.unknownTarget").value(true));
  }

  @Test
  @DisplayName("DELETE_不存在404_存在200")
  void deleteRule() throws Exception {
    when(repository.existsById(9L)).thenReturn(false);
    mvc.perform(delete("/api/v1/tool-policy/rules/9")).andExpect(status().isNotFound());

    when(repository.existsById(1L)).thenReturn(true);
    mvc.perform(delete("/api/v1/tool-policy/rules/1")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("GET_overview_effective视图含declared/effective/removed与命中原因")
  void overview_effectiveView() throws Exception {
    mvc.perform(get("/api/v1/tool-policy"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.effective[0].agentName").value("kb-tester"))
        .andExpect(jsonPath("$.data.effective[0].declared.length()").value(2))
        .andExpect(jsonPath("$.data.effective[0].effective[0]").value("read_file"))
        .andExpect(jsonPath("$.data.effective[0].removed[0].toolName").value("shell"))
        .andExpect(jsonPath("$.data.effective[0].removed[0].reason").value("命中全局禁用规则（shell）"));
  }

  private static Profile profile(String name, List<String> tools) {
    return new Profile(
        name,
        "d",
        null,
        new Profile.ProviderRef("mock", "mock", null),
        tools,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(5, 20));
  }
}
