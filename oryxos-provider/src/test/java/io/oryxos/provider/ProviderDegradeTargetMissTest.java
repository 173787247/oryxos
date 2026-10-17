package io.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.cost.AuditLlmCostSource;
import io.oryxos.core.cost.BudgetScope;
import io.oryxos.core.cost.CostAwareLlmCallAuditor;
import io.oryxos.core.cost.CostLedgerService;
import io.oryxos.core.cost.CostProperties;
import io.oryxos.core.cost.InMemoryCostLedgerStore;
import io.oryxos.core.cost.OverBudgetAction;
import io.oryxos.core.metrics.MetricsRecorder;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.ModelPricing;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.provider.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 预算闸点名的降级目标未注册：这是持久性配置错误，不是值得换家的瞬时故障。
 *
 * <p>失败发生在 {@code chatOnce} 之前，不补记则审计、失败指标与 LLM span 一起为零（宪法 V）；分类上按 {@link
 * ProviderNotFoundException}「点名报错，绝不静默换家」的契约上抛，不参与「未知异常偏向切换」默认。
 *
 * <p>装配与生产一致：真实账本、真实装饰器、记录器；成功调用会新增账本行，所以「不新增账本行」不是空断言。
 */
class ProviderDegradeTargetMissTest {

  private static final String PROVIDER = "primary";
  private static final String MODEL = "gm-model";
  private static final String BACKUP = "backup";
  private static final String BACKUP_MODEL = "b-model";
  private static final String DEGRADE_PROVIDER = "cheap";
  private static final String DEGRADE_MODEL = "cheap-model";
  private static final String SESSION = "s-degrade";
  private static final String PROFILE = "degrade-agent";
  private static final long BUDGET_LIMIT_MICROS = 10L;

  private ChatModel primary;
  private ChatModel backup;
  private ChatModel cheap;
  private ProviderRegistry registry;
  private InMemoryCostLedgerStore store;
  private CostLedgerService ledger;
  private RecordingAuditor delegate;
  private RecordingMetrics metrics;
  private Map<String, ChatModel> byName;
  private SpringAiProviderServiceImpl service;

  @BeforeEach
  void setUp() {
    store = new InMemoryCostLedgerStore();
    CostProperties props = new CostProperties();
    props.setEnabled(true);
    props.setEnforcementEnabled(true);
    props.setOverBudgetAction(OverBudgetAction.DEGRADE);
    props.setDegradeProvider(DEGRADE_PROVIDER);
    props.setDegradeModel(DEGRADE_MODEL);
    CostProperties.BudgetRule rule = new CostProperties.BudgetRule();
    rule.setId("cap");
    rule.setScope(BudgetScope.MODEL);
    rule.setKey(MODEL);
    rule.setLimitMicros(BUDGET_LIMIT_MICROS);
    props.getBudgets().add(rule);
    ledger = new CostLedgerService(props, store, AuditLlmCostSource.NOOP);

    delegate = new RecordingAuditor();
    metrics = new RecordingMetrics();
    primary = mock(ChatModel.class);
    backup = mock(ChatModel.class);
    cheap = mock(ChatModel.class);
    registry = mock(ProviderRegistry.class);
    when(registry.find(PROVIDER))
        .thenReturn(Optional.of(new ProviderDef(PROVIDER, "key", "https://p", null)));
    when(registry.find(BACKUP))
        .thenReturn(Optional.of(new ProviderDef(BACKUP, "key", "https://b", null)));
    // 闸点名的降级目标故意不注册：这正是本用例要覆盖的配置错误
    when(registry.find(DEGRADE_PROVIDER)).thenReturn(Optional.empty());
    byName = Map.of(PROVIDER, primary, BACKUP, backup, DEGRADE_PROVIDER, cheap);

    service = serviceWith(def -> byName.get(def.name()));
  }

  @Test
  void 降级目标未注册_补记恰一条失败审计且不切备用() {
    spendBudgetToLimit();

    assertThatThrownBy(() -> service.chat(SESSION, profileWithBackup(), request()))
        .isInstanceOf(ProviderNotFoundException.class)
        .hasMessageContaining(DEGRADE_PROVIDER);

    // 分类决定「不可切换」：本家与备用一次都没被调用，备用连切换记录都不该有
    verify(primary, never()).call(any(Prompt.class));
    verify(backup, never()).call(any(Prompt.class));
    assertThat(metrics.switches).isEmpty();

    // 恰一条：补记发生在失败点，且只记一次
    assertThat(delegate.calls).hasSize(1);
    RecordedCall row = delegate.calls.get(0);
    assertThat(row.sessionId()).isEqualTo(SESSION);
    assertThat(row.profileName()).isEqualTo(PROFILE);
    // 审计行落闸点名的目标，排障直接看见是哪个 provider 没注册（而不是原 attempt）
    assertThat(row.provider()).isEqualTo(DEGRADE_PROVIDER);
    assertThat(row.model()).isEqualTo(DEGRADE_MODEL);
    assertThat(row.success()).isFalse();
    assertThat(row.usage()).isNull();
    assertThat(row.costMicros()).isNull();
    assertThat(row.errorMessage()).contains(DEGRADE_PROVIDER);
    assertThat(row.durationMs()).isLessThan(1000L);

    // 目标从未真正被调用：账本里只有 seed 那一行
    assertThat(store.all()).hasSize(1);
  }

  @Test
  void 对照_降级目标已注册_正常走降级且备用不被调用() {
    spendBudgetToLimit();
    when(registry.find(DEGRADE_PROVIDER))
        .thenReturn(Optional.of(new ProviderDef(DEGRADE_PROVIDER, "key", "https://c", null)));
    when(cheap.call(any(Prompt.class))).thenReturn(reply("cheap ok"));

    ProviderResponse response = service.chat(SESSION, profileWithBackup(), request());

    assertThat(response.text()).isEqualTo("cheap ok");
    // 证明上面「不新增账本行」不是接线没通：降级目标被真正调用并记了成功审计
    verify(primary, never()).call(any(Prompt.class));
    verify(backup, never()).call(any(Prompt.class));
    assertThat(delegate.calls).hasSize(1);
    RecordedCall row = delegate.calls.get(0);
    assertThat(row.provider()).isEqualTo(DEGRADE_PROVIDER);
    assertThat(row.model()).isEqualTo(DEGRADE_MODEL);
    assertThat(row.success()).isTrue();
    assertThat(store.all()).hasSize(2);
  }

  /** 流式路径与 chat 同形：闸点名的目标未注册时，同样要留一行审计、同样不换家。 */
  @Test
  void 流式降级目标未注册_同样补记一条失败审计且不切备用() {
    spendBudgetToLimit();

    assertThatThrownBy(
            () -> service.chatStream(SESSION, profileWithBackup(), request(), token -> {}))
        .isInstanceOf(ProviderNotFoundException.class)
        .hasMessageContaining(DEGRADE_PROVIDER);

    verify(primary, never()).stream(any(Prompt.class));
    verify(backup, never()).stream(any(Prompt.class));
    verify(backup, never()).call(any(Prompt.class));
    assertThat(metrics.switches).isEmpty();

    assertThat(delegate.calls).hasSize(1);
    RecordedCall row = delegate.calls.get(0);
    assertThat(row.provider()).isEqualTo(DEGRADE_PROVIDER);
    assertThat(row.model()).isEqualTo(DEGRADE_MODEL);
    assertThat(row.success()).isFalse();
    assertThat(row.usage()).isNull();
    assertThat(row.costMicros()).isNull();
    assertThat(row.errorMessage()).contains(DEGRADE_PROVIDER);
    assertThat(row.durationMs()).isLessThan(1000L);
    assertThat(store.all()).hasSize(1);
  }

  @Test
  void 对照_流式降级目标已注册_正常降级() {
    spendBudgetToLimit();
    when(registry.find(DEGRADE_PROVIDER))
        .thenReturn(Optional.of(new ProviderDef(DEGRADE_PROVIDER, "key", "https://c", null)));
    when(cheap.stream(any(Prompt.class))).thenReturn(Flux.just(reply("cheap "), reply("ok")));

    ProviderResponse response =
        service.chatStream(SESSION, profileWithBackup(), request(), token -> {});

    assertThat(response.text()).isEqualTo("cheap ok");
    verify(primary, never()).stream(any(Prompt.class));
    verify(backup, never()).stream(any(Prompt.class));
    assertThat(delegate.calls).hasSize(1);
    RecordedCall row = delegate.calls.get(0);
    assertThat(row.provider()).isEqualTo(DEGRADE_PROVIDER);
    assertThat(row.model()).isEqualTo(DEGRADE_MODEL);
    assertThat(row.success()).isTrue();
    assertThat(store.all()).hasSize(2);
  }

  @Test
  void chatOnce内抛出的PNFE_只记一条审计且不重复记账不切备用() {
    // findDef 之后目标失效（这里用 builder 抛 PNFE 代表）：审计已由 invokeChat 记过，
    // 外层 catch 只拒绝换家，绝不能再记一条
    SpringAiProviderServiceImpl failing =
        serviceWith(
            def -> {
              if (PROVIDER.equals(def.name())) {
                throw new ProviderNotFoundException(PROVIDER);
              }
              return byName.get(def.name());
            });

    assertThatThrownBy(() -> failing.chat(SESSION, profileWithBackup(), request()))
        .isInstanceOf(ProviderNotFoundException.class)
        .hasMessageContaining(PROVIDER);

    verify(backup, never()).call(any(Prompt.class));
    assertThat(metrics.switches).isEmpty();
    // 恰一条：invokeChat 的失败审计；外层 catch 若也记账这里就会变成两条
    assertThat(delegate.calls).hasSize(1);
    RecordedCall row = delegate.calls.get(0);
    assertThat(row.provider()).isEqualTo(PROVIDER);
    assertThat(row.model()).isEqualTo(MODEL);
    assertThat(row.success()).isFalse();
  }

  private SpringAiProviderServiceImpl serviceWith(Function<ProviderDef, ChatModel> builder) {
    SpringAiProviderServiceImpl impl =
        new SpringAiProviderServiceImpl(
            registry,
            builder,
            new ToolSchemaAdapter(),
            new CostAwareLlmCallAuditor(delegate, ledger, (p, m) -> Optional.empty()),
            (p, m) -> Optional.<ModelPricing>empty(),
            metrics);
    impl.setCostLedgerService(ledger);
    return impl;
  }

  private void spendBudgetToLimit() {
    ledger.recordLlm(
        "seed-session", PROFILE, PROVIDER, MODEL, new Usage(1, 1, 2), BUDGET_LIMIT_MICROS, null, 1);
    assertThat(store.all()).hasSize(1);
    assertThat(ledger.checkBudget(PROFILE, null, MODEL, null).isDegrade()).isTrue();
  }

  /** 已注册的备用：用来证明「不切备用」不是因为候选耗尽，而是分类决定。 */
  private static Profile profileWithBackup() {
    return profileWithFallback(new Profile.ProviderRef.FallbackRef(BACKUP, BACKUP_MODEL));
  }

  private static Profile profileWithFallback(Profile.ProviderRef.FallbackRef... fallbacks) {
    return new Profile(
        PROFILE,
        null,
        null,
        new Profile.ProviderRef(PROVIDER, MODEL, null, List.of(fallbacks)),
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

  private record RecordedCall(
      String sessionId,
      String profileName,
      String provider,
      String model,
      Usage usage,
      Long costMicros,
      boolean success,
      String errorMessage,
      long durationMs) {}

  private static final class RecordingAuditor implements LlmCallAuditor {

    private final List<RecordedCall> calls = new ArrayList<>();

    @Override
    public void record(
        String sessionId,
        String profileName,
        String provider,
        String model,
        Usage usage,
        Long costMicros,
        boolean success,
        String errorMessage,
        long durationMs) {
      calls.add(
          new RecordedCall(
              sessionId,
              profileName,
              provider,
              model,
              usage,
              costMicros,
              success,
              errorMessage,
              durationMs));
    }
  }

  private static final class RecordingMetrics implements MetricsRecorder {

    private final List<String> switches = new ArrayList<>();

    @Override
    public void recordFallbackSwitch(String from, String to) {
      switches.add(from + "->" + to);
    }
  }
}
