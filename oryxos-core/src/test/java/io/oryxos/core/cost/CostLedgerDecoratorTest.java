package io.oryxos.core.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.ToolInvocationAuditor;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.ModelPricing;
import io.oryxos.core.provider.PricingStore;
import io.oryxos.core.provider.Usage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins what the two cost-ledger decorators charge, and where they disagree.
 *
 * <p>Both decorators are the only production bridges into {@link CostLedgerService} (wired in
 * {@code OryxOsRuntime}); nothing else drives them, so the unit of behaviour worth guarding is
 * "which calls reach the ledger" rather than the ledger's own arithmetic (covered by {@link
 * CostLedgerServiceTest}).
 *
 * <p>They disagree on one axis: the tool side charges every call, including a call a gate blocked
 * (policy, authorization or approval) so it never executed, while the LLM side charges successful
 * calls only. {@link #toolAndLlmSidesDisagreeOnBlockedCalls()} asserts both halves side by side, so
 * a change to either one has to be a visible decision instead of silent drift. Whether a blocked
 * call should be charged at all is a product call that these tests record rather than settle.
 */
class CostLedgerDecoratorTest {

  private static final String SESSION = "sess-1";
  private static final String AGENT = "agent-a";
  private static final String TOOL = "read_file";
  private static final long TOOL_MICROS = 500L;
  private static final long DEFAULT_TOOL_MICROS = 90L;
  private static final long PRICE_VERSION = 7L;

  private InMemoryCostLedgerStore store;
  private CostProperties props;
  private CostLedgerService ledger;
  private RecordingToolInvocationAuditor toolDelegate;
  private RecordingLlmCallAuditor llmDelegate;

  @BeforeEach
  void setUp() {
    store = new InMemoryCostLedgerStore();
    props = new CostProperties();
    props.setEnabled(true);
    props.setEnforcementEnabled(true);
    props.setDefaultToolCostMicros(DEFAULT_TOOL_MICROS);
    Map<String, Long> toolCosts = new HashMap<>();
    toolCosts.put(TOOL, TOOL_MICROS);
    props.setToolCosts(toolCosts);
    ledger = new CostLedgerService(props, store, AuditLlmCostSource.NOOP);
    toolDelegate = new RecordingToolInvocationAuditor();
    llmDelegate = new RecordingLlmCallAuditor();
  }

  // ---------------------------------------------------------------- tool side

  @Test
  @DisplayName(
      "tool: every record overload charges, and forwards to its matching delegate overload")
  void toolChargesEveryOverload() {
    CostAwareToolInvocationAuditor auditor =
        new CostAwareToolInvocationAuditor(toolDelegate, ledger);

    auditor.record(SESSION, AGENT, TOOL, "{}", "ok", true, null, 11L);
    auditor.record(SESSION, AGENT, TOOL, "{}", null, false, "io error", null, 12L);
    auditor.record(SESSION, AGENT, TOOL, "{}", null, false, "denied", "policy", "local", "c1", 13L);

    assertEquals(3, toolRows().size());
    assertEquals(3 * TOOL_MICROS, toolCostMicros());
    assertEquals(36L, totalLatencyMs());
    assertEquals(3, toolUsageCount());
    // The blocked marker has to survive the decoration: collapsing the 9-arg call into the 8-arg
    // delegate overload would drop it before the audit row is written.
    assertEquals(List.of(8, 9, 11), toolDelegate.arities());
    assertEquals("policy", toolDelegate.calls.get(2).blockedBy());
    assertEquals("local", toolDelegate.calls.get(2).executionBackend());
    assertEquals("c1", toolDelegate.calls.get(2).containerId());
  }

  @Test
  @DisplayName("tool: a successful call is charged the per-tool price, not the default")
  void toolChargesSuccessWithPerToolPrice() {
    new CostAwareToolInvocationAuditor(toolDelegate, ledger)
        .record(SESSION, AGENT, TOOL, "{}", "ok", true, null, 30L);

    assertEquals(1, toolRows().size());
    CostLedgerEntry row = toolRows().get(0);
    assertEquals(CostSourceKind.TOOL, row.sourceKind());
    assertEquals(TOOL, row.sourceRef());
    assertEquals(TOOL_MICROS, row.toolCostMicros());
    assertEquals(0L, row.llmCostMicros());
    assertEquals(30L, row.latencyMs());
    assertEquals(SESSION, row.sessionId());
    assertEquals(AGENT, row.agentName());
    assertNotNull(row.createdAt());
  }

  @Test
  @DisplayName("tool: an unknown tool name falls back to the default tool price")
  void toolChargesUnknownToolAtDefaultPrice() {
    new CostAwareToolInvocationAuditor(toolDelegate, ledger)
        .record(SESSION, AGENT, "shell", "{}", "ok", true, null, 5L);

    assertEquals(DEFAULT_TOOL_MICROS, toolRows().get(0).toolCostMicros());
  }

  @Test
  @DisplayName("tool: a failed call is charged too")
  void toolChargesFailedCall() {
    new CostAwareToolInvocationAuditor(toolDelegate, ledger)
        .record(SESSION, AGENT, TOOL, "{}", null, false, "io error", null, 7L);

    assertEquals(1, toolRows().size());
    assertEquals(TOOL_MICROS, toolRows().get(0).toolCostMicros());
    assertEquals(1, toolUsageCount());
    assertFalse(toolDelegate.calls.get(0).success());
  }

  @Test
  @DisplayName("tool: a call blocked before execution is charged whatever the block marker")
  void toolChargesBlockedCallThatNeverRan() {
    CostAwareToolInvocationAuditor auditor =
        new CostAwareToolInvocationAuditor(toolDelegate, ledger);
    // Every marker ToolExecutor sets when the gate refuses: policy denial (020), authorization
    // denial (039) and the approval gate (042). None of them reaches the tool.
    for (String blockedBy : List.of("policy", "authz", "approval")) {
      auditor.record(
          SESSION,
          AGENT,
          TOOL,
          "{\"path\":\"/etc/passwd\"}",
          null,
          false,
          "blocked before execution",
          blockedBy,
          0L);
    }

    // Current semantics: after(...) reads neither success nor blockedBy, so such a call still adds
    // a
    // TOOL row and a call count, and the tool-usage KPI counts it. Pinned so the fork with the LLM
    // side stays visible; see the class javadoc.
    assertEquals(3, toolRows().size());
    assertEquals(3 * TOOL_MICROS, toolCostMicros());
    assertEquals(3, toolUsageCount());
    assertEquals(
        List.of("policy", "authz", "approval"),
        toolDelegate.calls.stream().map(RecordedToolCall::blockedBy).toList());
    assertFalse(toolDelegate.calls.get(0).success());
    assertNull(toolDelegate.calls.get(0).resultJson());
  }

  @Test
  @DisplayName("tool: flag off writes the audit row and leaves the ledger empty")
  void toolFlagOffIsNoopForLedger() {
    props.setEnabled(false);

    new CostAwareToolInvocationAuditor(toolDelegate, ledger)
        .record(SESSION, AGENT, TOOL, "{}", "ok", true, null, 3L);

    assertTrue(store.all().isEmpty());
    assertEquals(1, toolDelegate.calls.size());
  }

  @Test
  @DisplayName("tool: a null ledger is tolerated and the audit row is still written")
  void toolNullLedgerIsTolerated() {
    new CostAwareToolInvocationAuditor(toolDelegate, null)
        .record(SESSION, AGENT, TOOL, "{}", null, false, "denied", "policy", 4L);

    assertTrue(store.all().isEmpty());
    assertEquals(1, toolDelegate.calls.size());
    assertEquals("policy", toolDelegate.calls.get(0).blockedBy());
  }

  // ----------------------------------------------------------------- llm side

  @Test
  @DisplayName("llm: a successful call is charged with the price version found for the model")
  void llmChargesSuccessWithPriceVersion() {
    new CostAwareLlmCallAuditor(llmDelegate, ledger, pricingHit())
        .record(SESSION, AGENT, "deepseek", "chat", new Usage(10, 5, 15), 900L, true, null, 40L);

    assertEquals(1, llmRows().size());
    CostLedgerEntry row = llmRows().get(0);
    assertEquals(CostSourceKind.LLM, row.sourceKind());
    assertEquals("deepseek/chat", row.sourceRef());
    assertEquals(900L, row.llmCostMicros());
    assertEquals(0L, row.toolCostMicros());
    assertEquals(10, row.promptTokens());
    assertEquals(5, row.completionTokens());
    assertEquals(15, row.totalTokens());
    assertEquals(PRICE_VERSION, row.priceVersion());
    assertEquals(40L, row.latencyMs());
    assertEquals(1, llmCallCount());
  }

  @Test
  @DisplayName("llm: a model with no pricing row is still charged, with a null price version")
  void llmChargesSuccessWithoutPricingRow() {
    new CostAwareLlmCallAuditor(llmDelegate, ledger, (provider, model) -> Optional.empty())
        .record(SESSION, AGENT, "deepseek", "chat", new Usage(10, 5, 15), 900L, true, null, 40L);

    assertEquals(1, llmRows().size());
    assertNull(llmRows().get(0).priceVersion());
    assertEquals(900L, llmRows().get(0).llmCostMicros());
  }

  @Test
  @DisplayName("llm: a missing pricing store is charged too, with a null price version")
  void llmChargesSuccessWithoutPricingStore() {
    new CostAwareLlmCallAuditor(llmDelegate, ledger, null)
        .record(SESSION, AGENT, "deepseek", "chat", new Usage(10, 5, 15), 900L, true, null, 40L);

    assertEquals(1, llmRows().size());
    assertNull(llmRows().get(0).priceVersion());
  }

  @Test
  @DisplayName("llm: a call with no usage and no cost is charged as a zero-price row")
  void llmChargesSuccessWithNullUsageAndCost() {
    new CostAwareLlmCallAuditor(llmDelegate, ledger, pricingHit())
        .record(SESSION, AGENT, "deepseek", "chat", null, null, true, null, 40L);

    assertEquals(1, llmRows().size());
    CostLedgerEntry row = llmRows().get(0);
    assertEquals(0L, row.llmCostMicros());
    assertNull(row.promptTokens());
    assertNull(row.totalTokens());
  }

  @Test
  @DisplayName("llm: a failed call is not charged")
  void llmSkipsFailedCall() {
    new CostAwareLlmCallAuditor(llmDelegate, ledger, pricingHit())
        .record(
            SESSION, AGENT, "deepseek", "chat", new Usage(10, 5, 15), 900L, false, "http 500", 40L);

    assertTrue(llmRows().isEmpty());
    assertEquals(1, llmDelegate.calls.size());
    assertFalse(llmDelegate.calls.get(0).success());
    assertEquals("http 500", llmDelegate.calls.get(0).errorMessage());
  }

  @Test
  @DisplayName("llm: flag off writes the audit row and leaves the ledger empty")
  void llmFlagOffIsNoopForLedger() {
    props.setEnabled(false);

    new CostAwareLlmCallAuditor(llmDelegate, ledger, pricingHit())
        .record(SESSION, AGENT, "deepseek", "chat", new Usage(10, 5, 15), 900L, true, null, 40L);

    assertTrue(store.all().isEmpty());
    assertEquals(1, llmDelegate.calls.size());
  }

  @Test
  @DisplayName("llm: a null ledger is tolerated and the audit row is still written")
  void llmNullLedgerIsTolerated() {
    new CostAwareLlmCallAuditor(llmDelegate, null, pricingHit())
        .record(SESSION, AGENT, "deepseek", "chat", new Usage(10, 5, 15), 900L, true, null, 40L);

    assertTrue(store.all().isEmpty());
    assertEquals(1, llmDelegate.calls.size());
  }

  // ------------------------------------------------------------- the fork

  @Test
  @DisplayName(
      "fork: the tool side charges failed and blocked calls, the llm side only the success")
  void toolAndLlmSidesDisagreeOnBlockedCalls() {
    CostAwareToolInvocationAuditor tool = new CostAwareToolInvocationAuditor(toolDelegate, ledger);
    tool.record(SESSION, AGENT, TOOL, "{}", "ok", true, null, 1L);
    tool.record(SESSION, AGENT, TOOL, "{}", null, false, "io error", null, 2L);
    tool.record(SESSION, AGENT, TOOL, "{}", null, false, "denied", "policy", 3L);

    // Two states is all the LLM side has: it decides on success alone, and its record signature
    // carries no block marker to decide on. (A budget refusal does not even get here -- the gate
    // throws before the audited call site -- so a blocked LLM call leaves no ledger row by a
    // different route than the one asserted below.)
    CostAwareLlmCallAuditor llm = new CostAwareLlmCallAuditor(llmDelegate, ledger, pricingHit());
    llm.record(SESSION, AGENT, "deepseek", "chat", new Usage(10, 5, 15), 900L, true, null, 4L);
    llm.record(
        SESSION, AGENT, "deepseek", "chat", new Usage(10, 5, 15), 900L, false, "http 500", 5L);

    assertEquals(3, toolRows().size());
    assertEquals(1, llmRows().size());
    assertEquals(3, toolUsageCount());
    assertEquals(1, llmCallCount());
    assertEquals(3 * TOOL_MICROS, toolCostMicros());
    assertEquals(900L, ledger.query(null).llmCostMicros());
    assertEquals(3 * TOOL_MICROS + 900L, ledger.query(null).totalCostMicros());
  }

  // ------------------------------------------------------------- fixtures

  private List<CostLedgerEntry> toolRows() {
    return store.all().stream().filter(e -> e.sourceKind() == CostSourceKind.TOOL).toList();
  }

  private List<CostLedgerEntry> llmRows() {
    return store.all().stream().filter(e -> e.sourceKind() == CostSourceKind.LLM).toList();
  }

  private long toolCostMicros() {
    return ledger.query(null).toolCostMicros();
  }

  private long totalLatencyMs() {
    return ledger.query(null).totalLatencyMs();
  }

  private long toolUsageCount() {
    return ledger.toolUsage(null, true).stream().mapToLong(ToolUsageEntry::callCount).sum();
  }

  private long llmCallCount() {
    return ledger.query(null).llmCallCount();
  }

  private static PricingStore pricingHit() {
    return (provider, model) ->
        Optional.of(new ModelPricing(provider, model, 1.0, 2.0, PRICE_VERSION));
  }

  /** Records which delegate overload the decorator used, so a dropped block marker is visible. */
  private record RecordedToolCall(
      String sessionId,
      String profileName,
      String toolName,
      String inputJson,
      String resultJson,
      boolean success,
      String errorMessage,
      String blockedBy,
      String executionBackend,
      String containerId,
      long durationMs) {}

  private static final class RecordingToolInvocationAuditor implements ToolInvocationAuditor {

    private final List<RecordedToolCall> calls = new ArrayList<>();
    private final List<Integer> arities = new ArrayList<>();

    @Override
    public void record(
        String sessionId,
        String profileName,
        String toolName,
        String inputJson,
        String resultJson,
        boolean success,
        String errorMessage,
        long durationMs) {
      arities.add(8);
      calls.add(
          new RecordedToolCall(
              sessionId,
              profileName,
              toolName,
              inputJson,
              resultJson,
              success,
              errorMessage,
              null,
              null,
              null,
              durationMs));
    }

    @Override
    public void record(
        String sessionId,
        String profileName,
        String toolName,
        String inputJson,
        String resultJson,
        boolean success,
        String errorMessage,
        String blockedBy,
        long durationMs) {
      arities.add(9);
      calls.add(
          new RecordedToolCall(
              sessionId,
              profileName,
              toolName,
              inputJson,
              resultJson,
              success,
              errorMessage,
              blockedBy,
              null,
              null,
              durationMs));
    }

    @Override
    public void record(
        String sessionId,
        String profileName,
        String toolName,
        String inputJson,
        String resultJson,
        boolean success,
        String errorMessage,
        String blockedBy,
        String executionBackend,
        String containerId,
        long durationMs) {
      arities.add(11);
      calls.add(
          new RecordedToolCall(
              sessionId,
              profileName,
              toolName,
              inputJson,
              resultJson,
              success,
              errorMessage,
              blockedBy,
              executionBackend,
              containerId,
              durationMs));
    }

    private List<Integer> arities() {
      return List.copyOf(arities);
    }
  }

  private record RecordedLlmCall(
      String sessionId,
      String profileName,
      String provider,
      String model,
      Usage usage,
      Long costMicros,
      boolean success,
      String errorMessage,
      long durationMs) {}

  private static final class RecordingLlmCallAuditor implements LlmCallAuditor {

    private final List<RecordedLlmCall> calls = new ArrayList<>();

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
          new RecordedLlmCall(
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
