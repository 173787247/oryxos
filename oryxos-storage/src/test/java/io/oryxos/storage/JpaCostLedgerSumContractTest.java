package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.core.cost.CostAttributionQuery;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成本账本求和契约（#476，两库同套用例）。
 *
 * <p>{@code CostLedgerService.checkBudget} 在每次 LLM 调用上对同一份账本求两次和，而账本是 append-only、随调用 增长；{@code GET
 * /api/v1/cost/attribution} 的无条件查询也走这条求和的另一半。求和若在 Java 里做（先 {@code find(...)}
 * 再累加），就把命中的明细行整表水合进堆。本用例用 Hibernate 的实体加载计数钉死「求和不加载明细 行」，同时校对求和结果与筛选条件——不能为了不加载而算错。
 *
 * <p>NOT_SUPPORTED 压制测试事务：明细行必须提交后再由另一次会话读取，否则它们就在持久化上下文里、查询不落到数据库， 「加载了几行」也就量不出来。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class JpaCostLedgerSumContractTest {

  private static final int LEDGER_ROWS = 200;
  private static final long LLM_MICROS_PER_ROW = 7L;
  private static final long TOOL_MICROS_PER_ROW = 3L;
  private static final int LLM_CALLS_PER_TRACE = 100;
  private static final long FIRST_TRACE_MICROS = 5L;
  private static final long SECOND_TRACE_MICROS = 9L;

  private static final CostAttributionQuery ALL_ENTRIES =
      new CostAttributionQuery(null, null, null, null, null, null);

  @Autowired private CostLedgerEntryRepository ledgerEntries;
  @Autowired private LlmCallRepository llmCalls;
  @Autowired private EntityManagerFactory entityManagerFactory;

  private JpaCostLedgerStore store;
  private JpaAuditLlmCostSource auditCosts;

  @BeforeEach
  void setUp() {
    store = new JpaCostLedgerStore(ledgerEntries);
    auditCosts = new JpaAuditLlmCostSource(llmCalls);
    llmCalls.deleteAll();
    ledgerEntries.deleteAll();
  }

  @Test
  @DisplayName("sum_在库内求和_不把明细行水合进内存")
  void sumsTheLedgerWithoutHydratingItsRows() {
    seedLedgerRows(LEDGER_ROWS);

    Statistics statistics = statistics();
    long loadedBefore = statistics.getEntityLoadCount();
    long llm = store.sumLlmCostMicros(ALL_ENTRIES);
    long tool = store.sumToolCostMicros(ALL_ENTRIES);
    long loaded = statistics.getEntityLoadCount() - loadedBefore;

    assertThat(llm).isEqualTo(LEDGER_ROWS * LLM_MICROS_PER_ROW);
    assertThat(tool).isEqualTo(LEDGER_ROWS * TOOL_MICROS_PER_ROW);
    assertThat(loaded).isZero();
  }

  @Test
  @DisplayName("sum_按条件求和_只累计命中的行")
  void filteredSumCountsMatchingRowsOnly() {
    seedLedgerRows(LEDGER_ROWS);

    long agentA =
        store.sumLlmCostMicros(new CostAttributionQuery(null, null, "agent-a", null, null, null));
    long agentB =
        store.sumLlmCostMicros(new CostAttributionQuery(null, null, "agent-b", null, null, null));

    assertThat(agentA).isEqualTo(LEDGER_ROWS / 2 * LLM_MICROS_PER_ROW);
    assertThat(agentB).isEqualTo(LEDGER_ROWS / 2 * LLM_MICROS_PER_ROW);
    assertThat(agentA + agentB).isEqualTo(LEDGER_ROWS * LLM_MICROS_PER_ROW);
  }

  @Test
  @DisplayName("find_仍然返回明细行_归因列表不受求和改走聚合影响")
  void findStillReturnsTheDetailedRows() {
    seedLedgerRows(LEDGER_ROWS);

    assertThat(store.find(ALL_ENTRIES)).hasSize(LEDGER_ROWS);
    assertThat(store.find(new CostAttributionQuery(null, null, "agent-a", null, null, null)))
        .hasSize(LEDGER_ROWS / 2);
  }

  @Test
  @DisplayName("sumCostMicrosByTraceId_按 trace 在库内求和_不加载全部 llm_calls")
  void auditSumUsesTheTraceWithoutLoadingEveryCall() {
    seedLlmCalls();

    Statistics statistics = statistics();
    long loadedBefore = statistics.getEntityLoadCount();
    long total = auditCosts.sumCostMicrosByTraceId("trace-1");
    long loaded = statistics.getEntityLoadCount() - loadedBefore;

    assertThat(total).isEqualTo(LLM_CALLS_PER_TRACE * FIRST_TRACE_MICROS);
    assertThat(loaded).isZero();
  }

  @Test
  @DisplayName("sumCostMicrosByTraceId_未知或空 trace 返回 0")
  void auditSumReturnsZeroForUnknownOrBlankTrace() {
    seedLlmCalls();

    assertThat(auditCosts.sumCostMicrosByTraceId("trace-missing")).isZero();
    assertThat(auditCosts.sumCostMicrosByTraceId(null)).isZero();
    assertThat(auditCosts.sumCostMicrosByTraceId(" ")).isZero();
  }

  private Statistics statistics() {
    return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
  }

  private void seedLedgerRows(int count) {
    List<CostLedgerEntryEntity> rows = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      CostLedgerEntryEntity row = new CostLedgerEntryEntity();
      row.setRunId("run-" + (i % 2));
      row.setAgentName(i % 2 == 0 ? "agent-a" : "agent-b");
      row.setProvider("deepseek");
      row.setModel("deepseek-flash");
      row.setSourceKind("LLM");
      row.setLlmCostMicros(LLM_MICROS_PER_ROW);
      row.setToolCostMicros(TOOL_MICROS_PER_ROW);
      row.setLatencyMs(1L);
      rows.add(row);
    }
    ledgerEntries.saveAll(rows);
  }

  private void seedLlmCalls() {
    List<LlmCall> rows = new ArrayList<>(LLM_CALLS_PER_TRACE * 2);
    for (int i = 0; i < LLM_CALLS_PER_TRACE; i++) {
      rows.add(llmCall("trace-1", FIRST_TRACE_MICROS));
      rows.add(llmCall("trace-2", SECOND_TRACE_MICROS));
    }
    llmCalls.saveAll(rows);
  }

  private static LlmCall llmCall(String traceId, long costMicros) {
    LlmCall call = new LlmCall();
    call.setSessionId("session-" + traceId);
    call.setProvider("deepseek");
    call.setModel("deepseek-flash");
    call.setTraceId(traceId);
    call.setCostMicros(costMicros);
    call.setSuccess(true);
    call.setDurationMs(1L);
    return call;
  }
}
