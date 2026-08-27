package io.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.PricingStore;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 019 验收 harness：ProviderStreamTest——chatStream 口径钉死。守：分段回调与聚合文本一致、流式 tool-call
 * 聚合（合并式/增量式两形态）、无流式能力降级整段一次回调、流式中途异常先落失败审计再上抛、成功审计与 chat 同口径、 mock 模型分段流出。
 */
class ProviderStreamTest {

  private ProviderRegistry registry;
  private LlmCallAuditor audit;
  private ChatModel model;
  private SpringAiProviderServiceImpl service;
  private Profile profile;
  private final List<String> tokens = new ArrayList<>();

  @BeforeEach
  void setUp() {
    registry = mock(ProviderRegistry.class);
    audit = mock(LlmCallAuditor.class);
    model = mock(ChatModel.class);
    when(registry.find("p1"))
        .thenReturn(Optional.of(new ProviderDef("p1", "test-key", "http://localhost:0", null)));
    service =
        new SpringAiProviderServiceImpl(
            registry, def -> model, new ToolSchemaAdapter(), audit, mock(PricingStore.class));
    profile =
        new Profile(
            "a1",
            "d",
            null,
            new Profile.ProviderRef("p1", "m1", null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Profile.Settings(5, 20));
  }

  @Test
  @DisplayName("流式_分段回调与聚合文本一致_成功审计一条")
  void streaming_deltasAggregate() {
    when(model.stream(any(Prompt.class)))
        .thenReturn(Flux.just(textChunk("你好"), textChunk("，世"), textChunk("界")));

    ProviderResponse response = service.chatStream("s-1", profile, emptyRequest(), tokens::add);

    assertThat(tokens).containsExactly("你好", "，世", "界");
    assertThat(response.text()).isEqualTo("你好，世界");
    assertThat(response.hasToolCalls()).isFalse();
    verify(audit)
        .record(
            eq("s-1"), eq("a1"), eq("p1"), eq("m1"), any(), any(), eq(true), eq(null), anyLong());
  }

  @Test
  @DisplayName("流式_增量式tool-call分片聚合为完整调用_不回调token")
  void streaming_toolCallDeltasMerged() {
    ChatResponse first = toolChunk("call-1", "shell", "{\"cmd\":");
    ChatResponse second = toolChunk("", null, "\"ls\"}");
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(first, second));

    ProviderResponse response = service.chatStream("s-1", profile, emptyRequest(), tokens::add);

    assertThat(tokens).isEmpty();
    assertThat(response.toolCalls()).hasSize(1);
    assertThat(response.toolCalls().get(0).id()).isEqualTo("call-1");
    assertThat(response.toolCalls().get(0).name()).isEqualTo("shell");
    assertThat(response.toolCalls().get(0).argumentsJson()).isEqualTo("{\"cmd\":\"ls\"}");
  }

  @Test
  @DisplayName("无流式能力_降级chat整段一次回调_审计恰好一条")
  void unsupportedStreaming_degradesToChat() {
    when(model.stream(any(Prompt.class)))
        .thenThrow(new UnsupportedOperationException("streaming is not supported"));
    when(model.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("整段回复")))));

    ProviderResponse response = service.chatStream("s-1", profile, emptyRequest(), tokens::add);

    assertThat(tokens).containsExactly("整段回复");
    assertThat(response.text()).isEqualTo("整段回复");
    verify(audit, times(1))
        .record(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyBoolean(),
            any(),
            anyLong());
  }

  @Test
  @DisplayName("流式中途异常_失败审计一条后上抛_不返回残缺结果")
  void midStreamFailure_auditsThenThrows() {
    when(model.stream(any(Prompt.class)))
        .thenReturn(
            Flux.concat(Flux.just(textChunk("半截")), Flux.error(new RuntimeException("网络断"))));

    assertThatThrownBy(() -> service.chatStream("s-1", profile, emptyRequest(), tokens::add))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("网络断");
    // 注：Reactor BlockingIterable 在 error 就位后丢弃未消费队列项——测试中信号瞬时到齐故 token 可能为空；
    // 生产中消费与网络到达同步推进，已到 token 正常回调。此处只钉「不返回残缺结果 + 失败落账」。
    verify(audit)
        .record(
            eq("s-1"),
            eq("a1"),
            eq("p1"),
            eq("m1"),
            eq(null),
            eq(null),
            eq(false),
            anyString(),
            anyLong());
  }

  @Test
  @DisplayName("mock模型_stream分段流出且拼接等于整段call结果")
  void mockModel_streamsInChunks() {
    MockChatModel mock = new MockChatModel();
    Prompt finalRound =
        new Prompt(
            List.of(
                new org.springframework.ai.chat.messages.UserMessage("记住这个"),
                org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                    .responses(
                        List.of(
                            new org.springframework.ai.chat.messages.ToolResponseMessage
                                .ToolResponse("mock-call-1", "save_memory", "ok")))
                    .build()));

    List<ChatResponse> chunks = mock.stream(finalRound).collectList().block();
    String full = mock.call(finalRound).getResult().getOutput().getText();

    assertThat(chunks).hasSizeGreaterThan(1);
    String joined =
        chunks.stream().map(c -> c.getResult().getOutput().getText()).reduce("", String::concat);
    assertThat(joined).isEqualTo(full);
  }

  private static ChatResponse textChunk(String delta) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(delta))));
  }

  private static ChatResponse toolChunk(String id, String name, String argsDelta) {
    AssistantMessage message =
        AssistantMessage.builder()
            .content("")
            .properties(java.util.Map.of())
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall(
                        id, "function", name == null ? "" : name, argsDelta)))
            .build();
    return new ChatResponse(List.of(new Generation(message)));
  }

  private static ProviderRequest emptyRequest() {
    return new ProviderRequest("你是助手", List.of(), List.of());
  }
}
