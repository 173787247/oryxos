package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.AgentValidation;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.SessionManager;
import io.oryxos.web.GlobalExceptionHandler;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 端点切片：动态管理 CRUD 薄转发（冲突→400、不存在→404）；invoke 走无状态编排入口。 */
class AgentApiControllerTest {

  private AgentLifecycleService lifecycle;
  private AgentService agentService;
  private SessionManager sessionManager;
  private MockMvc mvc;

  private static Profile profile(String name) {
    return new Profile(
        name,
        null,
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }

  /** 带 persona 7 字段的 Profile（025：AgentView.persona 投影断言用）。 */
  private static Profile profileWithPersona(String name) {
    return new Profile(
        name,
        null,
        null,
        new Profile.Persona("客服小林", "售后支持", "耐心", "温和", "客户第一", "不编造事实", "回答问题简洁有礼"),
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }

  @BeforeEach
  void setUp() {
    lifecycle = mock(AgentLifecycleService.class);
    agentService = mock(AgentService.class);
    sessionManager = mock(SessionManager.class);
    ProfileRegistry registry = new ProfileRegistry(Map.of("ops", profile("ops")));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new AgentApiController(
                    lifecycle,
                    agentService,
                    sessionManager,
                    registry,
                    mock(io.oryxos.core.memory.MemoryService.class),
                    mock(io.oryxos.core.agent.AgentExecutionService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("create 成功_返回 AgentView")
  void create_success_returnsAgentView() throws Exception {
    when(lifecycle.create(eq("demo"), any(), any(), any(), any())).thenReturn(profile("demo"));

    mvc.perform(
            post("/api/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"demo\",\"description\":\"一个测试 Agent\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("demo"));
  }

  @Test
  @DisplayName("create name 冲突_返回400")
  void create_conflict_returns400() throws Exception {
    when(lifecycle.create(eq("dup"), any(), any(), any(), any()))
        .thenThrow(new IllegalArgumentException("Agent 已存在: dup"));

    mvc.perform(
            post("/api/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"dup\",\"description\":\"x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("get 不存在_返回404")
  void get_unknown_returns404() throws Exception {
    when(lifecycle.get("ghost")).thenReturn(Optional.empty());

    mvc.perform(get("/api/v1/agents/ghost"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("delete 不存在_返回404、不触发删除")
  void delete_unknown_returns404() throws Exception {
    when(lifecycle.get("ghost")).thenReturn(Optional.empty());

    mvc.perform(delete("/api/v1/agents/ghost")).andExpect(status().isNotFound());
    verify(lifecycle, never()).delete(any());
  }

  @Test
  @DisplayName("update 不存在_返回404")
  void update_unknown_returns404() throws Exception {
    when(lifecycle.get("ghost")).thenReturn(Optional.empty());

    mvc.perform(
            put("/api/v1/agents/ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentMarkdown\":\"x\"}"))
        .andExpect(status().isNotFound());
    verify(lifecycle, never()).update(any(), any());
  }

  @Test
  @DisplayName("invoke 已存在 Agent_走无状态编排且不创建持久会话")
  void invokeKnownAgent_callsStatelessProcess() throws Exception {
    when(agentService.processStateless(eq("ops"), eq("查天气"))).thenReturn("晴");

    mvc.perform(
            post("/api/v1/agents/ops/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"查天气\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reply").value("晴"));
    verify(agentService).processStateless(eq("ops"), eq("查天气"));
    verify(sessionManager, never()).getOrCreate(eq("invoke"), any(), any());
    verify(agentService, never()).process(any(), any());
  }

  @Test
  @DisplayName("invoke 不存在的 Agent_返回404")
  void invokeUnknownAgent_returns404() throws Exception {
    mvc.perform(
            post("/api/v1/agents/ghost/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hi\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    verify(agentService, never()).processStateless(any(), any());
    verify(agentService, never()).process(any(), any());
  }

  @Test
  @DisplayName("invoke 消息超32KB_返回400")
  void invokeOver32kb_returns400() throws Exception {
    String huge = "x".repeat(32 * 1024 + 1);
    mvc.perform(
            post("/api/v1/agents/ops/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + huge + "\"}"))
        .andExpect(status().isBadRequest());
    verify(agentService, never()).processStateless(any(), any());
    verify(agentService, never()).process(any(), any());
  }

  @Test
  @DisplayName("updateBasic 成功_返回 AgentView")
  void updateBasic_success_returnsAgentView() throws Exception {
    when(lifecycle.get("demo")).thenReturn(Optional.of(profile("demo")));
    when(lifecycle.updateBasicInfo(eq("demo"), eq("新描述"), eq("openai"), eq("gpt-4o")))
        .thenReturn(profile("demo"));

    mvc.perform(
            put("/api/v1/agents/demo/basic")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"新描述\",\"provider\":\"openai\",\"model\":\"gpt-4o\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("demo"));
    verify(lifecycle).updateBasicInfo(eq("demo"), eq("新描述"), eq("openai"), eq("gpt-4o"));
  }

  @Test
  @DisplayName("updateBasic 不存在_返回404")
  void updateBasic_unknown_returns404() throws Exception {
    when(lifecycle.get("ghost")).thenReturn(Optional.empty());

    mvc.perform(
            put("/api/v1/agents/ghost/basic")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"x\",\"provider\":\"openai\",\"model\":\"gpt-4o\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    verify(lifecycle, never()).updateBasicInfo(any(), any(), any(), any());
  }

  @Test
  @DisplayName("updatePersona 成功_返回带 persona 投影的 AgentView")
  void updatePersona_success_returnsAgentViewWithPersona() throws Exception {
    when(lifecycle.get("demo")).thenReturn(Optional.of(profile("demo")));
    when(lifecycle.updatePersona(eq("demo"), any())).thenReturn(profileWithPersona("demo"));

    mvc.perform(
            put("/api/v1/agents/demo/persona")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"客服小林\",\"role\":\"售后支持\",\"traits\":\"耐心\",\"tone\":\"温和\","
                        + "\"values\":\"客户第一\",\"boundaries\":\"不编造事实\",\"sampleStyle\":\"回答问题简洁有礼\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.persona.name").value("客服小林"))
        .andExpect(jsonPath("$.data.persona.role").value("售后支持"))
        .andExpect(jsonPath("$.data.persona.traits").value("耐心"))
        .andExpect(jsonPath("$.data.persona.tone").value("温和"))
        .andExpect(jsonPath("$.data.persona.values").value("客户第一"))
        .andExpect(jsonPath("$.data.persona.boundaries").value("不编造事实"))
        .andExpect(jsonPath("$.data.persona.sampleStyle").value("回答问题简洁有礼"));
    verify(lifecycle).updatePersona(eq("demo"), any());
  }

  @Test
  @DisplayName("updatePersona 不存在_返回404")
  void updatePersona_unknown_returns404() throws Exception {
    when(lifecycle.get("ghost")).thenReturn(Optional.empty());

    mvc.perform(
            put("/api/v1/agents/ghost/persona")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"客服小林\",\"role\":\"售后支持\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    verify(lifecycle, never()).updatePersona(any(), any());
  }

  @Test
  @DisplayName("025：GET 详情含 persona 七字段投影")
  void get_returnsPersonaProjection() throws Exception {
    when(lifecycle.get("ops")).thenReturn(Optional.of(profileWithPersona("ops")));

    mvc.perform(get("/api/v1/agents/ops"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.persona.name").value("客服小林"))
        .andExpect(jsonPath("$.data.persona.role").value("售后支持"))
        .andExpect(jsonPath("$.data.persona.traits").value("耐心"));
  }

  @Test
  @DisplayName("025：无 persona 的 Agent 详情 persona 投影为 null")
  void get_noPersona_projectionNull() throws Exception {
    when(lifecycle.get("ops")).thenReturn(Optional.of(profile("ops")));

    mvc.perform(get("/api/v1/agents/ops"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.persona").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  @DisplayName("025：import-preview 不落盘，且带 dry-run 校验结果（含解析出的 provider/model）")
  void importPreview_doesNotPersist() throws Exception {
    when(lifecycle.defaultProvider()).thenReturn("deepseek");
    when(lifecycle.validateAgent(eq("architect"), any()))
        .thenReturn(AgentValidation.ok(profile("architect")));

    mvc.perform(
            post("/api/v1/agents/import-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceContent\":\"---\\nname: 软件架构师\\ndescription: x\\n---\\n## 核心使命\\n正文\",\"name\":\"architect\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("architect"))
        .andExpect(jsonPath("$.data.agentMarkdown").isNotEmpty())
        .andExpect(jsonPath("$.data.validation.valid").value(true))
        .andExpect(jsonPath("$.data.validation.provider").value("deepseek"))
        .andExpect(jsonPath("$.data.validation.model").value("deepseek-chat"))
        .andExpect(jsonPath("$.data.expert.boundaries").exists())
        .andExpect(jsonPath("$.data.expert.sampleStyle").exists());

    verify(lifecycle, never()).importAgent(any(), any()); // 不落盘：importAgent 从未被调用
  }

  @Test
  @DisplayName("025：import-preview 校验失败仍 200，结果体现在 validation.valid=false + message")
  void importPreview_validationFail_still200() throws Exception {
    when(lifecycle.defaultProvider()).thenReturn("deepseek");
    when(lifecycle.validateAgent(eq("architect"), any()))
        .thenReturn(AgentValidation.fail("frontmatter 缺 name 段"));

    mvc.perform(
            post("/api/v1/agents/import-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceContent\":\"---\\ndescription: x\\n---\\n## 核心使命\\n正文\",\"name\":\"architect\"}"))
        .andExpect(status().isOk()) // 预览永远 200，不抛 400
        .andExpect(jsonPath("$.data.validation.valid").value(false))
        .andExpect(jsonPath("$.data.validation.message").value("frontmatter 缺 name 段"))
        .andExpect(jsonPath("$.data.validation.provider").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  @DisplayName("025：import 落盘返回 AgentView")
  void import_persistsAndReturnsAgentView() throws Exception {
    when(lifecycle.defaultProvider()).thenReturn("deepseek");
    when(lifecycle.importAgent(eq("architect"), any())).thenReturn(profile("architect"));

    mvc.perform(
            post("/api/v1/agents/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sourceContent\":\"---\\nname: 软件架构师\\ndescription: x\\n---\\n## 核心使命\\n正文\",\"name\":\"architect\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("architect"));
    verify(lifecycle).importAgent(eq("architect"), any());
  }
}
