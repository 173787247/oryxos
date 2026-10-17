package io.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.cost.AuditLlmCostSource;
import io.oryxos.core.cost.BudgetExceededException;
import io.oryxos.core.cost.BudgetScope;
import io.oryxos.core.cost.CostAwareLlmCallAuditor;
import io.oryxos.core.cost.CostLedgerService;
import io.oryxos.core.cost.CostProperties;
import io.oryxos.core.cost.InMemoryCostLedgerStore;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.provider.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 预算闸拒绝的审计痕迹：被拦下的调用从未发生，但必须留下一条 {@code success=false} 的 llm_calls 行。
 *
 * <p>装配与生产一致：真实账本、真实装饰器、记录器。
 *
 * <p>成功调用会新增账本行，所以「不记账本行」不是空断言。
 *
 * <p>装饰器在 {@code !success} 时早退，账本侧不收费。
 */
class ProviderBudgetAuditTest {

  private static final String PROVIDER = "primary";
  private static final String MODEL = "gm-model";
  private static final String BACKUP_MODEL = "b-model";
  private static final String SESSION = "s-1";
  private static final String PROFILE = "budget-agent";
  private static final long SEEDED_MICROS = 10L;
  private static final long BUDGET_LIMIT_MICROS = 10L;

  private ChatModel primary;
  private ChatModel backup;
  private InMemoryCostLedgerStore store;
  private CostLedgerService ledger;
  private RecordingAuditor delegate;
  private SpringAiProviderServiceImpl service;

  @BeforeEach
  void setUp() {
    store = new InMemoryCostLedgerStore();
    CostProperties props = new CostProperties();
    props.setEnabled(true);
    props.setEnforcementEnabled(true);
    CostProperties.BudgetRule rule = new CostProperties.BudgetRule();
    rule.setId("cap");
    rule.setScope(BudgetScope.MODEL);
    rule.setKey(MODEL);
    rule.setLimitMicros(BUDGET_LIMIT_MICROS);
    props.getBudgets().add(rule);
    ledger = new CostLedgerService(props, store, AuditLlmCostSource.NOOP);
    delegate = new RecordingAuditor();
    primary = mock(ChatModel.class);
    backup = mock(ChatModel.class);
    ProviderRegistry registry = mock(ProviderRegistry.class);
    when(registry.find(PROVIDER))
        .thenReturn(Optional.of(new ProviderDef(PROVIDER, "key", "https://p", null)));
    when(registry.find("backup"))
        .thenReturn(Optional.of(new ProviderDef("backup", "key", "https://b", null)));
    Map<String, ChatModel> byName = Map.of(PROVIDER, primary, "backup", backup);
    service =
        new SpringAiProviderServiceImpl(
            registry,
            def -> byName.get(def.name()),
            new ToolSchemaAdapter(),
            new CostAwareLlmCallAuditor(delegate, ledger, (p, m) -> Optional.empty()),
            (p, m) -> Optional.empty());
    service.setCostLedgerService(ledger);
  }

  @Test
  void 预算拒绝_落一行失败审计且模型未被调用() {
    spendBudgetToLimit();

    assertThatThrownBy(() -> service.chat(SESSION, profileWithFallback(), request()))
        .isInstanceOf(BudgetExceededException.class)
        .hasMessageContaining("budget exceeded: rule=cap");

    // 闸在调用前拒绝：模型一次都没被调用
    verify(primary, never()).call(any(Prompt.class));

    assertThat(delegate.calls).hasSize(1);
    RecordedCall row = delegate.calls.get(0);
    assertThat(row.sessionId()).isEqualTo(SESSION);
    assertThat(row.profileName()).isEqualTo(PROFILE);
    assertThat(row.provider()).isEqualTo(PROVIDER);
    assertThat(row.model()).isEqualTo(MODEL);
    assertThat(row.success()).isFalse();
    // 未被调用 ⇒ 无 token、无成本；原因保留规则 id 与花销，排障不必再去翻日志
    assertThat(row.usage()).isNull();
    assertThat(row.costMicros()).isNull();
    assertThat(row.errorMessage())
        .startsWith("budget exceeded: rule=cap")
        .contains("spent=" + SEEDED_MICROS)
        .contains("limit=" + BUDGET_LIMIT_MICROS);
    // 时长只覆盖闸检查，不是一次模型调用的延迟
    assertThat(row.durationMs()).isLessThan(1000L);

    // 账本里只有 seed 那一行：被拒调用不收费（装饰器 !success 早退）
    assertThat(store.all()).hasSize(1);
  }

  @Test
  void 预算内正常失败_审计带提供商异常而非预算文案() {
    when(primary.call(any(Prompt.class))).thenThrow(new IllegalStateException("upstream 500"));

    assertThatThrownBy(() -> service.chat(SESSION, profileWithFallback(), request()))
        .isInstanceOf(IllegalStateException.class);

    verify(primary).call(any(Prompt.class));
    assertThat(delegate.calls).hasSize(1);
    RecordedCall row = delegate.calls.get(0);
    assertThat(row.success()).isFalse();
    // 两条失败路径不会混为一谈：一条带预算原因、一条带真实调用异常
    assertThat(row.errorMessage()).isEqualTo("upstream 500");
    assertThat(row.errorMessage()).doesNotContain("budget exceeded");
    // 失败调用同样不记账本行——与预算拒绝同为零账本行，但审计消息可区分
    assertThat(store.all()).isEmpty();
  }

  @Test
  void 预算拒绝不切备用_备用未被调用且只落一条审计() {
    spendBudgetToLimit();
    Profile profile =
        profileWithFallback(new Profile.ProviderRef.FallbackRef("backup", BACKUP_MODEL));

    assertThatThrownBy(() -> service.chat(SESSION, profile, request()))
        .isInstanceOf(BudgetExceededException.class);

    verify(primary, never()).call(any(Prompt.class));
    verify(backup, never()).call(any(Prompt.class));
    // 每条尝试各落一条就会把预算拒绝记成两次失败；终态出口只记一次
    assertThat(delegate.calls).hasSize(1);
  }

  @Test
  void 对照_成功调用会新增账本行() {
    when(primary.call(any(Prompt.class))).thenReturn(reply("ok"));

    ProviderResponse response = service.chat(SESSION, profileWithFallback(), request());

    assertThat(response.text()).isEqualTo("ok");
    assertThat(delegate.calls).hasSize(1);
    assertThat(delegate.calls.get(0).success()).isTrue();
    // 证明账本接线是活的：上面「不新增行」不是因为没有接线
    assertThat(store.all()).hasSize(1);
  }

  private void spendBudgetToLimit() {
    ledger.recordLlm(
        "seed-session", PROFILE, PROVIDER, MODEL, new Usage(1, 1, 2), SEEDED_MICROS, null, 1);
    assertThat(store.all()).hasSize(1);
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
}
