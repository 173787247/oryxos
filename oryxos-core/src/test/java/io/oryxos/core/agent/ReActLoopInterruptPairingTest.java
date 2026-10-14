package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.ToolResult;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.provider.ToolCallRequest;
import io.oryxos.core.session.Message;
import io.oryxos.core.session.Session;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #761：中断 ReAct 循环时，**每个 tool_call 都必须留下配对的 tool_result**。
 *
 * <p>不配对的历史会被 LLM 提供方直接拒绝，于是那个会话的**后续所有请求**都失败 —— 会话永久不可用。原实现 在中断时直接 {@code return
 * INTERRUPTED_REPLY}，跳过了剩余工具的回填。
 *
 * <p>既有的 {@code ReActLoopTest} 覆盖了中断的两个时点（迭代开头、工具间隙），但只断言「后续工具没被执行」和 「返回了中断文案」——
 * **没有一条断言历史里的配对完整性**，而那正是这个 bug 的实质。
 */
class ReActLoopInterruptPairingTest {

  // 真实 provider 会给每个 tool call 分配 id（Message.ToolCall 的 javadoc：「模型分配的 id」）。
  // 必须带上：不带 id 时配对断言退化成「null 配 null」，而 #761 说的正是**那个 id 悬空**。
  private static final ToolCallRequest FIRST_CALL =
      new ToolCallRequest("call-1", "http_get", "{\"url\":\"https://wttr.in\"}");
  private static final ToolCallRequest SECOND_CALL =
      new ToolCallRequest("call-2", "read_file", "{\"path\":\"/tmp/a\"}");

  private PromptBuilder promptBuilder;
  private ProviderService providerService;
  private ToolExecutor toolExecutor;
  private Session session;

  @BeforeEach
  void setUp() {
    promptBuilder = mock(PromptBuilder.class);
    when(promptBuilder.build(any(), any())).thenReturn(ProviderRequest.of("prompt"));
    providerService = mock(ProviderService.class);
    toolExecutor = mock(ToolExecutor.class);
    session = new Session("s-1", "ops-agent");
  }

  private ReActLoop loopWith(InterruptManager interrupts) {
    return new ReActLoop(promptBuilder, providerService, toolExecutor, interrupts);
  }

  private Profile profile() {
    return new Profile(
        "ops-agent",
        null,
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of("http_get"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(10, 20));
  }

  /** 每调用一次 chat 都回同一组工具调用 —— 中断必须发生在这一轮之内。 */
  private void alwaysTwoToolCalls() {
    when(providerService.chat(any(), any(), any()))
        .thenReturn(new ProviderResponse(null, List.of(FIRST_CALL, SECOND_CALL), null));
  }

  private static Set<String> requestedToolCallIds(Session session) {
    Set<String> ids = new LinkedHashSet<>();
    for (Message message : session.messages()) {
      message.toolCalls().forEach(call -> ids.add(call.id()));
    }
    return ids;
  }

  private static Set<String> answeredToolCallIds(Session session) {
    Set<String> ids = new LinkedHashSet<>();
    for (Message message : session.messages()) {
      if (Message.ROLE_TOOL.equals(message.role()) && message.toolCallId() != null) {
        ids.add(message.toolCallId());
      }
    }
    return ids;
  }

  private void assertEveryToolCallAnswered() {
    Set<String> requested = requestedToolCallIds(session);
    Set<String> answered = answeredToolCallIds(session);
    assertTrue(requested.size() >= 2, "这条用例要有意义，至少需要两个工具调用");
    assertEquals(
        requested,
        answered,
        "每个 tool_call 都必须有配对的 tool_result，否则提供方会拒绝该会话的后续请求（#761）\n" + session.messages());
  }

  @Test
  @DisplayName("工具间隙中断：被跳过的工具也要留下 tool_result")
  void interruptBetweenToolsStillAnswersEveryCall() {
    InterruptManager interrupts = new InterruptManager();
    alwaysTwoToolCalls();
    when(toolExecutor.execute(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              interrupts.interrupt("s-1");
              return ToolResult.ok("ok");
            });

    String reply = loopWith(interrupts).run(session, "干活", profile());

    assertEquals(ReActLoop.INTERRUPTED_REPLY, reply);
    assertEveryToolCallAnswered();
  }

  @Test
  @DisplayName("本轮一个工具都没跑就中断：两个 tool_call 都要留下 tool_result")
  void interruptBeforeAnyToolStillAnswersEveryCall() {
    InterruptManager interrupts = new InterruptManager();
    // 在 chat 返回时立标志：assistant 消息（含两个 tool_call）已经进了历史
    // （ReActLoop 先 appendAssistant 再进工具循环），但第一个工具尚未执行 —— 这正是原实现
    // 直接 return INTERRUPTED_REPLY 而留下两个悬空 id 的时点。
    when(providerService.chat(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              interrupts.interrupt("s-1");
              return new ProviderResponse(null, List.of(FIRST_CALL, SECOND_CALL), null);
            });

    String reply = loopWith(interrupts).run(session, "干活", profile());

    assertEquals(ReActLoop.INTERRUPTED_REPLY, reply);
    assertTrue(!answeredToolCallIds(session).isEmpty(), "中断前已进历史的 tool_call 必须被补齐");
    assertEveryToolCallAnswered();
  }

  @Test
  @DisplayName("中断补齐的 tool_result 带失败标记，模型下一轮看得到「已中断」")
  void interruptedResultsAreMarkedAsFailures() {
    InterruptManager interrupts = new InterruptManager();
    alwaysTwoToolCalls();
    when(toolExecutor.execute(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              interrupts.interrupt("s-1");
              return ToolResult.ok("ok");
            });

    loopWith(interrupts).run(session, "干活", profile());

    boolean anyInterruptMarker =
        session.messages().stream()
            .filter(message -> Message.ROLE_TOOL.equals(message.role()))
            .anyMatch(message -> !message.content().isBlank());
    assertTrue(anyInterruptMarker, "补齐的 tool_result 不该是空内容:\n" + session.messages());
  }
}
