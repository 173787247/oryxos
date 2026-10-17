package io.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.routing.DataSensitivity;
import io.oryxos.core.routing.InMemoryRoutingDecisionStore;
import io.oryxos.core.routing.ModelRoutingService;
import io.oryxos.core.routing.ProviderModelRef;
import io.oryxos.core.routing.RoutingContext;
import io.oryxos.core.routing.RoutingProperties;
import io.oryxos.core.routing.TaskDifficulty;
import java.util.List;
import java.util.Map;
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
 * 051 routing × 023 fallback 的接缝：routing 开启后「尝试序列的第 0 位」不再等于「Profile 声明的主」。
 *
 * <p>route() 会把 Agent 声明的备用候选提权到第 0 位（{@code promotePreferred}：difficulty / latency / budget
 * degrade），也会按 residency 过滤、按成本重排。provider 侧若仍按位置判主（{@code findDef(attempt, i == 0)}），
 * 提权到首位且**未注册**的备用就会被当成「声明主未注册」→ 直抛 {@link ProviderNotFoundException}：整次调用硬失败、 声明主一次都不试，而抛出点在
 * fallback try 之外 ⇒ 审计行、失败指标与 LLM span 一起为零（宪法 V）。
 *
 * <p>装配用**真实** {@link ModelRoutingService}（只有 routing × provider 这个组合接通，才谈得上钉住接缝）： 提权经 {@code
 * oryxos.routing.difficulty-models} 配置 + {@link RoutingContext#open} 触发，过滤经 residency 白名单触发。
 */
class ProviderRoutingPrimaryTest {

  private static final String SESSION = "s-routing";
  private static final String AGENT = "routing-agent";
  private static final String PRIMARY = "primary";
  private static final String PRIMARY_MODEL = "p-model";
  private static final String BACKUP = "backup";
  private static final String BACKUP_MODEL = "b-model";
  private static final String GHOST = "ghost";
  private static final String GHOST_MODEL = "g-model";

  private ChatModel primary;
  private ChatModel backup;
  private LlmCallAuditor audit;
  private RoutingProperties routingProps;
  private SpringAiProviderServiceImpl service;

  @BeforeEach
  void setUp() {
    primary = mock(ChatModel.class);
    backup = mock(ChatModel.class);
    audit = mock(LlmCallAuditor.class);
    ProviderRegistry registry = mock(ProviderRegistry.class);
    when(registry.find(anyString())).thenReturn(Optional.empty());
    when(registry.find(PRIMARY))
        .thenReturn(Optional.of(new ProviderDef(PRIMARY, "key", "https://p", null)));
    when(registry.find(BACKUP))
        .thenReturn(Optional.of(new ProviderDef(BACKUP, "key", "https://b", null)));
    Map<String, ChatModel> byName = Map.of(PRIMARY, primary, BACKUP, backup);
    service =
        new SpringAiProviderServiceImpl(
            registry,
            def -> byName.get(def.name()),
            new ToolSchemaAdapter(),
            audit,
            (p, m) -> Optional.empty());

    routingProps = new RoutingProperties();
    routingProps.setEnabled(true);
    service.setModelRoutingService(
        new ModelRoutingService(
            routingProps, new InMemoryRoutingDecisionStore(16), (p, m) -> Optional.empty()));
  }

  @Test
  @DisplayName("提权到首位的未注册备用：跳过它继续试声明主，调用成功并留下成功审计")
  void promotedUnregisteredFallbackIsSkippedAndPrimaryStillRuns() {
    preferByDifficulty(GHOST, GHOST_MODEL);
    when(primary.call(any(Prompt.class))).thenReturn(reply("主回复"));

    ProviderResponse response;
    try (RoutingContext.Scope ignored =
        RoutingContext.open(TaskDifficulty.HIGH, null, null, null)) {
      response = service.chat(SESSION, profileWithFallback(GHOST, GHOST_MODEL), request());
    }

    assertThat(response.text()).isEqualTo("主回复");
    verify(primary, times(1)).call(any(Prompt.class));
    // 缺陷形态下这里根本走不到：提权到首位的 ghost 被当成声明主 → 直抛 PNFE，且一条审计都没有。
    verify(audit, times(1))
        .record(
            eq(SESSION),
            eq(AGENT),
            eq(PRIMARY),
            eq(PRIMARY_MODEL),
            any(),
            any(),
            eq(true),
            isNull(),
            anyLong());
    verifyNeverAudited(GHOST);
  }

  @Test
  @DisplayName("提权到首位的已注册备用：确实用它（提权生效，不是被顺手跳过）")
  void promotedRegisteredFallbackIsActuallyUsed() {
    preferByDifficulty(BACKUP, BACKUP_MODEL);
    when(backup.call(any(Prompt.class))).thenReturn(reply("备用回复"));

    ProviderResponse response;
    try (RoutingContext.Scope ignored =
        RoutingContext.open(TaskDifficulty.HIGH, null, null, null)) {
      response = service.chat(SESSION, profileWithFallback(BACKUP, BACKUP_MODEL), request());
    }

    assertThat(response.text()).isEqualTo("备用回复");
    verify(backup, times(1)).call(any(Prompt.class));
    verify(primary, never()).call(any(Prompt.class));
  }

  @Test
  @DisplayName("routing 关闭：第 0 位仍是声明主，行为与引入 routing 前一致")
  void routingDisabledKeepsPositionZeroAsDeclaredPrimary() {
    routingProps.setEnabled(false);
    when(primary.call(any(Prompt.class))).thenReturn(reply("主回复"));

    ProviderResponse response =
        service.chat(SESSION, profileWithFallback(BACKUP, BACKUP_MODEL), request());

    assertThat(response.text()).isEqualTo("主回复");
    verify(primary, times(1)).call(any(Prompt.class));
    verify(backup, never()).call(any(Prompt.class));
  }

  @Test
  @DisplayName("声明主未注册：仍点名报错（不静默换家），但失败要留一条 success=false 审计")
  void unregisteredDeclaredPrimaryThrowsButLeavesOneFailureRow() {
    routingProps.setEnabled(false);

    assertThatThrownBy(() -> service.chat(SESSION, profile(GHOST, GHOST_MODEL), request()))
        .isInstanceOf(ProviderNotFoundException.class)
        .hasMessageContaining(GHOST);

    verify(primary, never()).call(any(Prompt.class));
    verify(backup, never()).call(any(Prompt.class));
    // 修复前：抛出在 fallback try 之外，审计/指标/span 三者一起为零——一次配置事故在账上不存在。
    verify(audit, times(1))
        .record(
            eq(SESSION),
            eq(AGENT),
            eq(GHOST),
            eq(GHOST_MODEL),
            isNull(),
            isNull(),
            eq(false),
            contains(GHOST),
            anyLong());
  }

  @Test
  @DisplayName("同 provider 不同 model 的备用被提权：仍按「是不是声明主」判定，审计归属声明主那条")
  void promotedSiblingWithSameProviderIsStillAFallback() {
    // 声明主 ghost/declared-model，备用 ghost/sibling-model——provider 名相同、模型不同。
    // 只比 provider 名会把提权后的备用当成声明主，点名的就是备用（sibling-model），错报成配置错误源头。
    preferByDifficulty(GHOST, "sibling-model");

    assertThatThrownBy(
            () -> {
              try (RoutingContext.Scope ignored =
                  RoutingContext.open(TaskDifficulty.HIGH, null, null, null)) {
                service.chat(
                    SESSION,
                    profile(
                        GHOST,
                        "declared-model",
                        new Profile.ProviderRef.FallbackRef(GHOST, "sibling-model")),
                    request());
              }
            })
        .isInstanceOf(ProviderNotFoundException.class)
        .hasMessageContaining(GHOST);

    // 位置 0（sibling-model）是备用：跳过不记；位置 1（declared-model）才是声明主：点名 + 记一行
    verify(audit, times(1))
        .record(
            eq(SESSION),
            eq(AGENT),
            eq(GHOST),
            eq("declared-model"),
            isNull(),
            isNull(),
            eq(false),
            contains(GHOST),
            anyLong());
    verifyNeverAuditedModel("sibling-model");
  }

  @Test
  @DisplayName("流式：提权到首位的未注册备用同样跳过，声明主照常出流")
  void streamSkipsPromotedUnregisteredFallbackToo() {
    preferByDifficulty(GHOST, GHOST_MODEL);
    when(primary.stream(any(Prompt.class))).thenReturn(Flux.just(reply("主流")));

    StringBuilder streamed = new StringBuilder();
    ProviderResponse response;
    try (RoutingContext.Scope ignored =
        RoutingContext.open(TaskDifficulty.HIGH, null, null, null)) {
      response =
          service.chatStream(
              SESSION, profileWithFallback(GHOST, GHOST_MODEL), request(), streamed::append);
    }

    assertThat(response.text()).isEqualTo("主流");
    assertThat(streamed.toString()).isEqualTo("主流");
    verify(audit, times(1))
        .record(
            eq(SESSION),
            eq(AGENT),
            eq(PRIMARY),
            eq(PRIMARY_MODEL),
            any(),
            any(),
            eq(true),
            isNull(),
            anyLong());
    verifyNeverAudited(GHOST);
  }

  @Test
  @DisplayName("没有任何候选可用（residency 滤掉声明主、剩余全未注册）：终局 PNFE 也留一行审计")
  void terminalNoCandidateFailureLeavesOneRow() {
    // residency 白名单只留 ghost：声明主被过滤掉，剩下的 ghost 未注册 ⇒ 一个候选都进不了调用
    routingProps.setResidencyProviders(List.of(GHOST));

    assertThatThrownBy(
            () -> {
              try (RoutingContext.Scope ignored =
                  RoutingContext.open(null, DataSensitivity.SENSITIVE, null, null)) {
                service.chat(SESSION, profileWithFallback(GHOST, GHOST_MODEL), request());
              }
            })
        .isInstanceOf(ProviderNotFoundException.class)
        .hasMessageContaining(PRIMARY);

    verify(primary, never()).call(any(Prompt.class));
    // 归属声明主（与异常点名的 provider 一致）；durationMs 只含跳过耗时（真实调用没有发生）
    verify(audit, times(1))
        .record(
            eq(SESSION),
            eq(AGENT),
            eq(PRIMARY),
            eq(PRIMARY_MODEL),
            isNull(),
            isNull(),
            eq(false),
            contains(PRIMARY),
            anyLong());
  }

  @Test
  @DisplayName("流式：没有任何候选可用时，终局 PNFE 同样留一行审计")
  void streamTerminalNoCandidateFailureLeavesOneRow() {
    routingProps.setResidencyProviders(List.of(GHOST));

    assertThatThrownBy(
            () -> {
              try (RoutingContext.Scope ignored =
                  RoutingContext.open(null, DataSensitivity.SENSITIVE, null, null)) {
                service.chatStream(
                    SESSION, profileWithFallback(GHOST, GHOST_MODEL), request(), token -> {});
              }
            })
        .isInstanceOf(ProviderNotFoundException.class)
        .hasMessageContaining(PRIMARY);

    verify(audit, times(1))
        .record(
            eq(SESSION),
            eq(AGENT),
            eq(PRIMARY),
            eq(PRIMARY_MODEL),
            isNull(),
            isNull(),
            eq(false),
            contains(PRIMARY),
            anyLong());
  }

  private void preferByDifficulty(String provider, String model) {
    ProviderModelRef ref = new ProviderModelRef();
    ref.setProvider(provider);
    ref.setModel(model);
    routingProps.setDifficultyModels(Map.of("high", ref));
  }

  private void verifyNeverAudited(String provider) {
    verify(audit, never())
        .record(any(), any(), eq(provider), any(), any(), any(), anyBoolean(), any(), anyLong());
  }

  private void verifyNeverAuditedModel(String model) {
    verify(audit, never())
        .record(any(), any(), any(), eq(model), any(), any(), anyBoolean(), any(), anyLong());
  }

  private static Profile profileWithFallback(String provider, String model) {
    return profile(PRIMARY, PRIMARY_MODEL, new Profile.ProviderRef.FallbackRef(provider, model));
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
