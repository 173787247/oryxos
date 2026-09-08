package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.ModelPricing;
import io.oryxos.core.provider.PricingStore;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** 016 成本写时定格验收：SC-001 成本换算（微元）、舍入边界、null 分支（未定价 / 失败）。 */
class CostComputeTest {

  private ChatModel model;
  private LlmCallAuditor audit;
  private ProviderRegistry registry;
  private PricingStore pricingStore;

  @BeforeEach
  void setUp() {
    model = mock(ChatModel.class);
    audit = mock(LlmCallAuditor.class);
    registry = mock(ProviderRegistry.class);
    when(registry.find("deepseek"))
        .thenReturn(Optional.of(new ProviderDef("deepseek", "k", "https://x", null)));
    pricingStore = mock(PricingStore.class);
  }

  private ProviderService service() {
    return new SpringAiProviderServiceImpl(
        registry, def -> model, new ToolSchemaAdapter(), audit, pricingStore);
  }

  @Test
  @DisplayName("整数价格：1000 输入 + 2000 输出 × 1/2 元每百万 = 5000 微元")
  void integerPricesComputeMicros() {
    when(model.call(any(Prompt.class))).thenReturn(usageResponse(1000, 2000));
    when(pricingStore.find("deepseek", "model-x"))
        .thenReturn(Optional.of(new ModelPricing("deepseek", "model-x", 1.0, 2.0)));

    service().chat("s-1", profile(), ProviderRequest.of("hi"));

    assertEquals(5000L, capturedCostMicros());
  }

  @Test
  @DisplayName("小数价格：0.5 元每百万 × 1000 token 舍入到 500 微元")
  void fractionalPriceRoundsToMicro() {
    when(model.call(any(Prompt.class))).thenReturn(usageResponse(1000, 0));
    when(pricingStore.find("deepseek", "model-x"))
        .thenReturn(Optional.of(new ModelPricing("deepseek", "model-x", 0.5, null)));

    service().chat("s-1", profile(), ProviderRequest.of("hi"));

    assertEquals(500L, capturedCostMicros());
  }

  @Test
  @DisplayName("未配置该模型定价：成本记未计量（null）")
  void missingPricingIsNull() {
    when(model.call(any(Prompt.class))).thenReturn(usageResponse(1000, 2000));
    when(pricingStore.find("deepseek", "model-x")).thenReturn(Optional.empty());

    service().chat("s-1", profile(), ProviderRequest.of("hi"));

    assertNull(capturedCostMicros());
  }

  @Test
  @DisplayName("调用失败拿不到 usage：成本记未计量（null）")
  void failedCallIsNull() {
    when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));

    try {
      service().chat("s-1", profile(), ProviderRequest.of("hi"));
    } catch (RuntimeException expected) {
      // 失败上抛，审计先落
    }

    ArgumentCaptor<Long> cost = ArgumentCaptor.forClass(Long.class);
    verify(audit)
        .record(
            eq("s-1"),
            eq("test-agent"),
            eq("deepseek"),
            eq("model-x"),
            isNull(),
            cost.capture(),
            eq(false),
            any(),
            anyLong());
    assertNull(cost.getValue());
  }

  private Long capturedCostMicros() {
    ArgumentCaptor<Long> cost = ArgumentCaptor.forClass(Long.class);
    verify(audit)
        .record(
            eq("s-1"),
            eq("test-agent"),
            eq("deepseek"),
            eq("model-x"),
            any(),
            cost.capture(),
            eq(true),
            isNull(),
            anyLong());
    return cost.getValue();
  }

  private static Profile profile() {
    return new Profile(
        "test-agent",
        null,
        null,
        new Profile.ProviderRef("deepseek", "model-x", null),
        null,
        null,
        null,
        null,
        null,
        null,
        Profile.Settings.defaults());
  }

  private static ChatResponse usageResponse(int promptTokens, int completionTokens) {
    Usage usage =
        new Usage() {
          @Override
          public Integer getPromptTokens() {
            return promptTokens;
          }

          @Override
          public Integer getCompletionTokens() {
            return completionTokens;
          }

          @Override
          public Integer getTotalTokens() {
            return promptTokens + completionTokens;
          }

          @Override
          public Object getNativeUsage() {
            return null;
          }
        };
    ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(usage).build();
    return ChatResponse.builder()
        .generations(List.of(new Generation(new AssistantMessage("ok"))))
        .metadata(metadata)
        .build();
  }
}
