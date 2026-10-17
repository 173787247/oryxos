package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.agent.TraceContext;
import io.oryxos.core.cost.CostAwareLlmCallAuditor;
import io.oryxos.core.cost.CostLedgerService;
import io.oryxos.core.cost.CostProperties;
import io.oryxos.core.cost.CostReconcileResult;
import io.oryxos.core.cost.InMemoryCostLedgerStore;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.ModelPricing;
import io.oryxos.core.provider.PricingStore;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.provider.SpringAiProviderServiceImpl;
import io.oryxos.provider.ToolSchemaAdapter;
import io.oryxos.storage.JpaLlmCallAuditor;
import io.oryxos.storage.LlmCall;
import io.oryxos.storage.LlmCallRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 负单价下三处口径必须一致：审计（{@code llm_calls.cost_micros}）、账本（{@code cost_ledger_entries}）、 对账（{@code
 * CostLedgerService.reconcile}）。
 *
 * <p>这条链路此前是三分叉：provider 算出负数原样进审计，指标只在 {@code > 0} 时记录，账本却把负数夹成 0 —— 于是同一批负价调用在审计里是负数、在账本里是
 * 0，{@code reconcile} 永远 mismatch 且没有任何一处解释差额。 现在成本在唯一的生产出口（{@code
 * computeMicros}）夹到非负，审计、指标、账本消费同一个值，对账才对得上。
 *
 * <p>放在 boot 是因为只有这里的测试类路径同时有 provider（算成本）、storage（{@code llm_calls} 写入与求和）、
 * core（账本与对账）。这条测试故意驱动真实的三件套而不是各自 mock 掉：要证明的正是它们之间不再分叉。
 */
class NegativePriceReconcileTest {

  private static final String RUN = "run-negative-price";
  private static final String PROVIDER = "deepseek";
  private static final String MODEL = "model-x";
  private static final String SESSION = "s-1";

  private ChatModel model;
  private PricingStore pricingStore;
  private LlmCallRepository repository;
  private CostLedgerService ledger;
  private ProviderService provider;
  private final List<LlmCall> savedCalls = new ArrayList<>();

  @BeforeEach
  void setUp() {
    model = mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenReturn(usageResponse(1000, 2000));

    ProviderRegistry registry = mock(ProviderRegistry.class);
    when(registry.find(PROVIDER))
        .thenReturn(Optional.of(new ProviderDef(PROVIDER, "k", "https://x", null)));

    // 负单价：1000×(-1) + 2000×(-2) = -5000 微元，未夹平前会原样进审计。
    pricingStore = mock(PricingStore.class);
    when(pricingStore.find(PROVIDER, MODEL))
        .thenReturn(Optional.of(new ModelPricing(PROVIDER, MODEL, -1.0, -2.0)));

    // llm_calls 的写入与求和按真实实现：JpaLlmCallAuditor 原样存 costMicros，仓储的 JPQL 原样求和（都不夹负）。
    repository = mock(LlmCallRepository.class);
    when(repository.save(any(LlmCall.class)))
        .thenAnswer(
            inv -> {
              LlmCall row = inv.getArgument(0);
              savedCalls.add(row);
              return row;
            });
    when(repository.sumCostMicrosByTraceId(any()))
        .thenAnswer(
            inv ->
                savedCalls.stream()
                    .filter(row -> Objects.equals(row.getTraceId(), inv.getArgument(0)))
                    .mapToLong(row -> row.getCostMicros() == null ? 0L : row.getCostMicros())
                    .sum());

    CostProperties properties = new CostProperties();
    properties.setEnabled(true);
    ledger =
        new CostLedgerService(
            properties, new InMemoryCostLedgerStore(), repository::sumCostMicrosByTraceId);

    LlmCallAuditor auditor =
        new CostAwareLlmCallAuditor(new JpaLlmCallAuditor(repository), ledger, pricingStore);
    provider =
        new SpringAiProviderServiceImpl(
            registry, def -> model, new ToolSchemaAdapter(), auditor, pricingStore);
  }

  @Test
  @DisplayName("负单价：审计记 0 而不是负数，账本与审计合计相等，对账不再 mismatch")
  void negativePriceReconciles() {
    try (TraceContext.Scope scope = TraceContext.open(RUN)) {
      provider.chat(SESSION, profile(), ProviderRequest.of("hi"));
    }

    // 先断对账：负单价若原样进审计，这里就是 ledgerLlm=0 / auditLlm=-5000 的 mismatch。
    CostReconcileResult reconciled = ledger.reconcile(RUN);
    assertEquals(0L, reconciled.ledgerLlmCostMicros());
    assertEquals(0L, reconciled.auditLlmCostMicros());
    assertTrue(reconciled.matched(), reconciled.detail());

    // 审计行确实写了：cost 为 0（夹平）而不是 null（未定价）——否则上面的 0==0 不是同一件事。
    assertEquals(1, savedCalls.size());
    assertNotNull(savedCalls.get(0).getCostMicros());
    assertEquals(0L, savedCalls.get(0).getCostMicros());
  }

  @Test
  @DisplayName("正单价仍然照常计费，对账一致（证明夹平没有把正常成本也压成 0）")
  void positivePriceStillCharged() {
    when(pricingStore.find(PROVIDER, MODEL))
        .thenReturn(Optional.of(new ModelPricing(PROVIDER, MODEL, 1.0, 2.0)));

    try (TraceContext.Scope scope = TraceContext.open(RUN)) {
      provider.chat(SESSION, profile(), ProviderRequest.of("hi"));
    }

    assertEquals(5000L, savedCalls.get(0).getCostMicros());
    CostReconcileResult reconciled = ledger.reconcile(RUN);
    assertEquals(5000L, reconciled.ledgerLlmCostMicros());
    assertEquals(5000L, reconciled.auditLlmCostMicros());
    assertTrue(reconciled.matched(), reconciled.detail());
  }

  private static Profile profile() {
    return new Profile(
        "test-agent",
        null,
        null,
        new Profile.ProviderRef(PROVIDER, MODEL, null),
        null,
        null,
        null,
        null,
        null,
        null,
        Profile.Settings.defaults());
  }

  private static ChatResponse usageResponse(int promptTokens, int completionTokens) {
    org.springframework.ai.chat.metadata.Usage usage =
        new org.springframework.ai.chat.metadata.Usage() {
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
