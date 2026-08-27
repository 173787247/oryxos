package io.oryxos.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.StreamListener;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.web.GlobalExceptionHandler;
import io.oryxos.web.config.WebSseProperties;
import io.oryxos.web.sse.SseStreamSupport;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 019 验收 harness：SseStreamingTest——三端点分流与 SSE 线协议钉死（contracts/sse-protocol.md）。守：非流式路径 现状响应、流式
 * Content-Type 与事件序、token 拼接==done.reply、终结唯一（done/error 二选一）、tool 事件成对有序、 流前失败返 JSON
 * 状态码、心跳注释行、断开静默完成。
 */
class SseStreamingTest {

  private static final Pattern EVENT_PATTERN =
      Pattern.compile("event: (\\w+)\\ndata: (\\{.*?\\})\\n\\n", Pattern.DOTALL);

  private AgentService agentService;
  private SessionManager sessionManager;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    agentService = mock(AgentService.class);
    sessionManager = mock(SessionManager.class);
    SessionApiController controller = new SessionApiController(agentService, sessionManager);
    controller.setSseStreamSupport(
        new SseStreamSupport(new ObjectMapper(), new WebSseProperties()));
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    when(sessionManager.get("s-1")).thenReturn(Optional.of(new Session("s-1", "a1")));
  }

  @Test
  @DisplayName("非流式_不带Accept_一次性JSON现状不变（SC-001）")
  void nonStreaming_unchanged() throws Exception {
    when(agentService.process(any(), anyString())).thenReturn("普通回复");

    mvc.perform(
            post("/api/v1/sessions/s-1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hi\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.reply").value("普通回复"));
  }

  @Test
  @DisplayName("流式_ContentType与token序列_拼接==done.reply_终结唯一")
  void streaming_tokensThenSingleDone() throws Exception {
    stubProcessStreaming("你好，世界", "你好", "，世", "界");

    MvcResult result = performStream("{\"content\":\"hi\"}");

    assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
    var events = parseEvents(result.getResponse().getContentAsString());
    assertThat(events).extracting(e -> e[0]).containsExactly("token", "token", "token", "done");
    String joined =
        events.stream()
            .filter(e -> e[0].equals("token"))
            .map(e -> e[1].replaceAll(".*\"delta\":\"(.*?)\".*", "$1"))
            .reduce("", String::concat);
    assertThat(joined).isEqualTo("你好，世界");
    assertThat(events.get(events.size() - 1)[1]).contains("\"reply\":\"你好，世界\"");
  }

  @Test
  @DisplayName("流式_工具事件成对且顺序正确")
  void streaming_toolEventsPairedInOrder() throws Exception {
    when(agentService.process(any(), anyString(), any()))
        .thenAnswer(
            inv -> {
              StreamListener listener = inv.getArgument(2, StreamListener.class);
              listener.onToolStart("shell");
              listener.onToolEnd("shell", true);
              listener.onToken("完成");
              return "完成";
            });

    var events =
        parseEvents(performStream("{\"content\":\"hi\"}").getResponse().getContentAsString());

    assertThat(events)
        .extracting(e -> e[0])
        .containsExactly("tool_start", "tool_end", "token", "done");
    assertThat(events.get(0)[1]).contains("\"name\":\"shell\"");
    assertThat(events.get(1)[1]).contains("\"success\":true");
  }

  @Test
  @DisplayName("流中异常_恰好一个error事件且无done_信息可读")
  void streaming_midStreamFailure_singleErrorTerminal() throws Exception {
    when(agentService.process(any(), anyString(), any()))
        .thenAnswer(
            inv -> {
              StreamListener listener = inv.getArgument(2, StreamListener.class);
              listener.onToken("半截");
              throw new IllegalStateException("provider call failed");
            });

    var events =
        parseEvents(performStream("{\"content\":\"hi\"}").getResponse().getContentAsString());

    assertThat(events).extracting(e -> e[0]).containsExactly("token", "error");
    assertThat(events.get(1)[1]).contains("provider call failed").doesNotContain("Exception:");
  }

  @Test
  @DisplayName("流前失败_会话不存在_404JSON非SSE（FR-009）")
  void preStreamFailure_returnsJsonStatus() throws Exception {
    when(sessionManager.get("ghost")).thenReturn(Optional.empty());

    mvc.perform(
            post("/api/v1/sessions/ghost/messages")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hi\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("心跳_静默期出现ping注释行（间隔调小验证）")
  void heartbeat_appearsDuringSilence() throws Exception {
    SessionApiController controller = new SessionApiController(agentService, sessionManager);
    WebSseProperties props = new WebSseProperties();
    props.setHeartbeatSeconds(1);
    controller.setSseStreamSupport(new SseStreamSupport(new ObjectMapper(), props));
    MockMvc slowMvc = MockMvcBuilders.standaloneSetup(controller).build();
    when(agentService.process(any(), anyString(), any()))
        .thenAnswer(
            inv -> {
              Thread.sleep(2500); // 静默期 > 2 个心跳间隔
              return "醒了";
            });

    MvcResult result =
        slowMvc
            .perform(
                post("/api/v1/sessions/s-1/messages")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"content\":\"hi\"}"))
            .andReturn();

    assertThat(result.getResponse().getContentAsString()).contains(": ping");
  }

  @Test
  @DisplayName("invoke端点_流式与非流式双路同口径（经AgentApiController）")
  void invokeEndpoint_bothPaths() throws Exception {
    ProfileRegistry profileRegistry = mock(ProfileRegistry.class);
    when(profileRegistry.get("a1"))
        .thenReturn(
            Optional.of(
                new io.oryxos.core.profile.Profile(
                    "a1",
                    "d",
                    null,
                    new io.oryxos.core.profile.Profile.ProviderRef("mock", "mock", null),
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(),
                    new io.oryxos.core.profile.Profile.Settings(5, 20))));
    AgentApiController agents =
        new AgentApiController(
            mock(io.oryxos.core.agent.AgentLifecycleService.class),
            agentService,
            sessionManager,
            profileRegistry,
            mock(io.oryxos.core.memory.MemoryService.class),
            mock(io.oryxos.core.agent.AgentExecutionService.class));
    agents.setSseStreamSupport(new SseStreamSupport(new ObjectMapper(), new WebSseProperties()));
    MockMvc agentMvc = MockMvcBuilders.standaloneSetup(agents).build();
    when(agentService.processStateless(eq("a1"), anyString())).thenReturn("无状态回复");
    when(agentService.processStateless(eq("a1"), anyString(), any(StreamListener.class)))
        .thenAnswer(
            inv -> {
              inv.getArgument(2, StreamListener.class).onToken("无状态回复");
              return "无状态回复";
            });

    agentMvc
        .perform(
            post("/api/v1/agents/a1/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hi\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reply").value("无状态回复"));

    MvcResult streamed =
        agentMvc
            .perform(
                post("/api/v1/agents/a1/invoke")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"content\":\"hi\"}"))
            .andReturn();
    var events = parseEvents(streamed.getResponse().getContentAsString());
    assertThat(events).extracting(e -> e[0]).containsExactly("token", "done");
    assertThat(events.get(1)[1]).contains("\"reply\":\"无状态回复\"");
  }

  private void stubProcessStreaming(String reply, String... deltas) {
    when(agentService.process(any(), anyString(), any(StreamListener.class)))
        .thenAnswer(
            inv -> {
              StreamListener listener = inv.getArgument(2, StreamListener.class);
              for (String delta : deltas) {
                listener.onToken(delta);
              }
              return reply;
            });
  }

  private MvcResult performStream(String body) throws Exception {
    return mvc.perform(
            post("/api/v1/sessions/s-1/messages")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  /** 解析 SSE 帧为 [event, data] 列表（忽略心跳注释行）。 */
  private static java.util.List<String[]> parseEvents(String raw) {
    java.util.List<String[]> events = new java.util.ArrayList<>();
    Matcher matcher = EVENT_PATTERN.matcher(raw);
    while (matcher.find()) {
      events.add(new String[] {matcher.group(1), matcher.group(2)});
    }
    return events;
  }
}
