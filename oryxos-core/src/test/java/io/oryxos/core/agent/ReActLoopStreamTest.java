package io.oryxos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.ToolResult;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.provider.ToolCallRequest;
import io.oryxos.core.session.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 019 验收 harness：ReActLoopStreamTest——循环流式打点口径钉死。守：token 与工具回调顺序反映真实执行顺序、 tool_start/tool_end
 * 成对（成败两路）、listener==NOOP 时走 chat 且行为与原 run 等价、多轮 ReAct 连续回调。
 */
class ReActLoopStreamTest {

  private PromptBuilder promptBuilder;
  private ProviderService providerService;
  private ToolExecutor toolExecutor;
  private ReActLoop loop;
  private Profile profile;
  private Session session;
  private final List<String> events = new ArrayList<>();

  private final StreamListener recording =
      new StreamListener() {
        @Override
        public void onToken(String delta) {
          events.add("token:" + delta);
        }

        @Override
        public void onToolStart(String toolName) {
          events.add("start:" + toolName);
        }

        @Override
        public void onToolEnd(String toolName, boolean success) {
          events.add("end:" + toolName + ":" + success);
        }
      };

  @BeforeEach
  void setUp() {
    promptBuilder = mock(PromptBuilder.class);
    providerService = mock(ProviderService.class);
    toolExecutor = mock(ToolExecutor.class);
    loop = new ReActLoop(promptBuilder, providerService, toolExecutor);
    profile =
        new Profile(
            "stream-agent",
            "流式测试",
            null,
            new Profile.ProviderRef("mock", "mock", null),
            List.of("shell", "http_get"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Profile.Settings(5, 20));
    session = new Session("s-1", "stream-agent");
    when(promptBuilder.build(any(), any())).thenReturn(mock(ProviderRequest.class));
  }

  @Test
  @DisplayName("流式路径_token与工具回调顺序反映执行顺序_tool成对")
  void streaming_eventOrderReflectsExecution() {
    ProviderResponse round1 =
        new ProviderResponse("", List.of(new ToolCallRequest("c1", "shell", "{}")), null);
    ProviderResponse round2 = new ProviderResponse("最终回复", List.of(), null);
    stubChatStream(round1, round2);
    when(toolExecutor.execute(anyString(), anyString(), any()))
        .thenReturn(new ToolResult(true, "ok", null, false));

    String reply = loop.run(session, "做点事", profile, recording);

    assertThat(reply).isEqualTo("最终回复");
    assertThat(events).containsExactly("start:shell", "end:shell:true", "token:最终回复");
  }

  @Test
  @DisplayName("工具失败_onToolEnd携带success=false")
  void toolFailure_reportedInToolEnd() {
    ProviderResponse round1 =
        new ProviderResponse("", List.of(new ToolCallRequest("c1", "http_get", "{}")), null);
    ProviderResponse round2 = new ProviderResponse("收尾", List.of(), null);
    stubChatStream(round1, round2);
    when(toolExecutor.execute(anyString(), anyString(), any()))
        .thenReturn(new ToolResult(false, null, "boom", true));

    loop.run(session, "去失败", profile, recording);

    assertThat(events).contains("end:http_get:false");
  }

  @Test
  @DisplayName("listener为NOOP_走chat不走chatStream_返回值与原run一致")
  void noopListener_usesChatPath() {
    when(providerService.chat(anyString(), any(), any()))
        .thenReturn(new ProviderResponse("普通回复", List.of(), null));

    String viaOriginal = loop.run(session, "hi", profile);
    Session second = new Session("s-2", "stream-agent");
    String viaNoop = loop.run(second, "hi", profile, StreamListener.NOOP);

    assertThat(viaOriginal).isEqualTo("普通回复").isEqualTo(viaNoop);
    verify(providerService, never()).chatStream(anyString(), any(), any(), any());
  }

  @Test
  @DisplayName("多轮ReAct_多次LLM生成的token连续回调")
  void multiRound_tokensAcrossRounds() {
    ProviderResponse round1 =
        new ProviderResponse("先想想", List.of(new ToolCallRequest("c1", "shell", "{}")), null);
    ProviderResponse round2 = new ProviderResponse("答案", List.of(), null);
    stubChatStream(round1, round2);
    when(toolExecutor.execute(anyString(), anyString(), any()))
        .thenReturn(new ToolResult(true, "ok", null, false));

    loop.run(session, "多轮", profile, recording);

    assertThat(events).containsExactly("token:先想想", "start:shell", "end:shell:true", "token:答案");
  }

  /** 依次返回给定响应，并把每个响应的 text 通过 onToken 回调（模拟 provider 流式）。 */
  private void stubChatStream(ProviderResponse... responses) {
    var queue = new java.util.ArrayDeque<>(List.of(responses));
    when(providerService.chatStream(anyString(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              ProviderResponse next = queue.poll();
              @SuppressWarnings("unchecked")
              Consumer<String> onToken = inv.getArgument(3, Consumer.class);
              if (next.text() != null && !next.text().isEmpty()) {
                onToken.accept(next.text());
              }
              return next;
            });
  }
}
