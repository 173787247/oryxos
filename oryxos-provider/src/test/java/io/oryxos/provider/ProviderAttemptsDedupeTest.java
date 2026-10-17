package io.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.routing.InMemoryRoutingDecisionStore;
import io.oryxos.core.routing.ModelRoutingService;
import io.oryxos.core.routing.RoutingDecision;
import io.oryxos.core.routing.RoutingProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.web.client.ResourceAccessException;

/**
 * 023 尝试序列去重：同一个候选在 AGENT.md 里写了两次，只应打一轮。
 *
 * <p>为什么这是修复而不是「少调一次」的优化：第二轮面对的是同一个端点、同一个请求，拿不到任何新信息，只会多一条失败审计， 并在切换日志里留下「deepseek →
 * deepseek」这种自指——读日志的人得先分辨这是配置重复还是真的发生了切换。声明重复在 AGENT.md 里很常见（把主 provider 又抄进
 * fallback、复制粘贴备用条目），而这条路径此前是原样展开的。
 *
 * <p>去重键是 {@code (provider, model)} 一对：同 provider 不同 model 是两个合法候选（换模型是真实的重试手段，结果可能不同），
 * 因此下面有一个反向用例专门钉住「不被合并」。
 *
 * <p>routing：{@code route()} 只重排/过滤声明候选，既不新增也不删除重复，所以声明里的重复会原样进入 {@code attemptOrder}。用例 {@link
 * #duplicateSurvivingRoutingIsCalledOnce()} 先断言这条前提（决策里仍是 2 条候选）， 再断言 provider 侧只调用一次——不是硬造 routing
 * 产出的重复。
 */
class ProviderAttemptsDedupeTest {

  private static final String SESSION = "s-dedupe";
  private static final String AGENT = "dedupe-agent";
  private static final String PRIMARY = "primary";
  private static final String PRIMARY_MODEL = "p-model";
  private static final String BACKUP = "backup";
  private static final String BACKUP_MODEL = "b-model";
  private static final String SHARED = "shared";

  private ChatModel primary;
  private ChatModel backup;
  private ChatModel shared;
  private LlmCallAuditor audit;
  private RoutingProperties routingProps;
  private ModelRoutingService routing;
  private SpringAiProviderServiceImpl service;

  @BeforeEach
  void setUp() {
    primary = mock(ChatModel.class);
    backup = mock(ChatModel.class);
    shared = mock(ChatModel.class);
    audit = mock(LlmCallAuditor.class);
    ProviderRegistry registry = mock(ProviderRegistry.class);
    when(registry.find(anyString())).thenReturn(Optional.empty());
    when(registry.find(PRIMARY))
        .thenReturn(Optional.of(new ProviderDef(PRIMARY, "key", "https://p", null)));
    when(registry.find(BACKUP))
        .thenReturn(Optional.of(new ProviderDef(BACKUP, "key", "https://b", null)));
    when(registry.find(SHARED))
        .thenReturn(Optional.of(new ProviderDef(SHARED, "key", "https://s", null)));
    Map<String, ChatModel> byName = Map.of(PRIMARY, primary, BACKUP, backup, SHARED, shared);
    service =
        new SpringAiProviderServiceImpl(
            registry,
            def -> byName.get(def.name()),
            new ToolSchemaAdapter(),
            audit,
            (p, m) -> Optional.empty());

    routingProps = new RoutingProperties(); // 默认 enabled=false：覆盖 routing 关闭分支
    routing =
        new ModelRoutingService(
            routingProps, new InMemoryRoutingDecisionStore(16), (p, m) -> Optional.empty());
    service.setModelRoutingService(routing);
  }

  @Test
  @DisplayName("主 provider 又写进 fallback：尝试序列只出现一次，只调用一次，只留一条失败审计")
  void duplicateOfPrimaryInFallbacksRunsOnce() {
    ResourceAccessException down = new ResourceAccessException("primary down");
    when(primary.call(any(Prompt.class))).thenThrow(down);

    assertThatThrownBy(
            () ->
                service.chat(
                    SESSION,
                    profile(
                        PRIMARY,
                        PRIMARY_MODEL,
                        new Profile.ProviderRef.FallbackRef(PRIMARY, PRIMARY_MODEL)),
                    request()))
        .isSameAs(down);

    // 缺陷形态：同一端点被打两轮（第二次仍失败），审计两条，切换日志写「primary → primary」
    verify(primary, times(1)).call(any(Prompt.class));
    verify(audit, times(1))
        .record(
            eq(SESSION),
            eq(AGENT),
            eq(PRIMARY),
            eq(PRIMARY_MODEL),
            isNull(),
            isNull(),
            eq(false),
            any(),
            anyLong());
  }

  @Test
  @DisplayName("重复的 fallback 条目：失败后不因重复把同一备用端点在打一轮")
  void duplicateFallbackEntriesRunOnce() {
    when(primary.call(any(Prompt.class))).thenThrow(new ResourceAccessException("primary down"));
    when(backup.call(any(Prompt.class))).thenThrow(new ResourceAccessException("backup down"));

    assertThatThrownBy(
            () ->
                service.chat(
                    SESSION,
                    profile(
                        PRIMARY,
                        PRIMARY_MODEL,
                        new Profile.ProviderRef.FallbackRef(BACKUP, BACKUP_MODEL),
                        new Profile.ProviderRef.FallbackRef(BACKUP, BACKUP_MODEL)),
                    request()))
        .isInstanceOf(ResourceAccessException.class);

    verify(backup, times(1)).call(any(Prompt.class));
    verify(audit, times(2))
        .record(any(), any(), any(), any(), any(), any(), eq(false), any(), anyLong());
  }

  @Test
  @DisplayName("同 provider 不同 model：是两个合法候选，不得合并（按声明序各调一次）")
  void sameProviderDifferentModelIsNotDeduplicated() {
    when(shared.call(any(Prompt.class)))
        .thenThrow(new ResourceAccessException("m1 down"))
        .thenReturn(reply("m2 ok"));

    ProviderResponse response =
        service.chat(
            SESSION,
            profile(SHARED, "m1", new Profile.ProviderRef.FallbackRef(SHARED, "m2")),
            request());

    assertThat(response.text()).isEqualTo("m2 ok");
    ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
    verify(shared, times(2)).call(prompts.capture());
    // 两次尝试携带各自声明的 model，且顺序就是声明序（去重保留第一次出现的位置）
    assertThat(modelOf(prompts.getAllValues().get(0))).isEqualTo("m1");
    assertThat(modelOf(prompts.getAllValues().get(1))).isEqualTo("m2");
  }

  @Test
  @DisplayName("routing 开启：重复原样进 attemptOrder，但 provider 侧仍只调用一次")
  void duplicateSurvivingRoutingIsCalledOnce() {
    routingProps.setEnabled(true);
    ResourceAccessException down = new ResourceAccessException("primary down");
    when(primary.call(any(Prompt.class))).thenThrow(down);

    assertThatThrownBy(
            () ->
                service.chat(
                    SESSION,
                    profile(
                        PRIMARY,
                        PRIMARY_MODEL,
                        new Profile.ProviderRef.FallbackRef(PRIMARY, PRIMARY_MODEL)),
                    request()))
        .isSameAs(down);

    // 前提：routing 自己不去重（也不新增重复），决策里的尝试序列仍是声明原样 2 条
    List<RoutingDecision> decisions = routing.recent(1);
    assertThat(decisions).hasSize(1);
    assertThat(decisions.get(0).attemptOrder()).hasSize(2);
    // 去重发生在尝试序列进入 provider 循环之前，所以同一端点只打一轮
    verify(primary, times(1)).call(any(Prompt.class));
    verify(audit, times(1))
        .record(
            eq(SESSION),
            eq(AGENT),
            eq(PRIMARY),
            eq(PRIMARY_MODEL),
            isNull(),
            isNull(),
            eq(false),
            any(),
            anyLong());
  }

  private static String modelOf(Prompt prompt) {
    return ((OpenAiChatOptions) prompt.getOptions()).getModel();
  }

  private static Profile profile(
      String provider, String model, Profile.ProviderRef.FallbackRef... fallbacks) {
    return new Profile(
        AGENT,
        null,
        null,
        new Profile.ProviderRef(provider, model, null, List.of(fallbacks)),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        null);
  }

  private static ProviderRequest request() {
    return new ProviderRequest("sys", List.of(), List.of());
  }

  private static ChatResponse reply(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }
}
