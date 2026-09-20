package io.oryxos.core.routing;

import io.oryxos.core.agent.TraceContext;
import io.oryxos.core.cost.BudgetDecision;
import io.oryxos.core.cost.CostContext;
import io.oryxos.core.cost.CostLedgerService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.ModelPricing;
import io.oryxos.core.provider.PricingStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cost/difficulty/sensitivity/latency-aware model routing on top of Agent fallback allowlist
 * (#477). Default-off via {@link RoutingProperties#isEnabled()}.
 */
public final class ModelRoutingService {

  private final RoutingProperties properties;
  private final RoutingDecisionStore store;
  private final PricingStore pricingStore;
  private final AtomicReference<CostLedgerService> costLedger = new AtomicReference<>();

  public ModelRoutingService(
      RoutingProperties properties, RoutingDecisionStore store, PricingStore pricingStore) {
    this.properties = properties == null ? new RoutingProperties() : properties;
    this.store =
        store == null
            ? new InMemoryRoutingDecisionStore(this.properties.getDecisionLogSize())
            : store;
    this.pricingStore = pricingStore;
  }

  public void setCostLedgerService(CostLedgerService ledger) {
    costLedger.set(ledger);
  }

  public boolean isEnabled() {
    return properties.isEnabled();
  }

  /**
   * When disabled: returns profile primary+fallbacks unchanged and does not record. When enabled:
   * filters/reorders within the Agent allowlist and records an explainable decision.
   */
  public RoutingDecision route(Profile profile) {
    List<RoutingCandidate> declared = declaredCandidates(profile);
    if (!properties.isEnabled()) {
      return passthrough(profile, declared);
    }

    List<RoutingReason> reasons = new ArrayList<>();
    List<CandidateDisposition> dispositions = new ArrayList<>();
    List<RoutingCandidate> working = new ArrayList<>(declared);

    RoutingContext.State ctx = RoutingContext.current();
    DataSensitivity sensitivity = ctx == null ? null : ctx.sensitivity();
    TaskDifficulty difficulty = ctx == null ? null : ctx.difficulty();
    Integer ctxLatency = ctx == null ? null : ctx.maxLatencyMs();
    Long estimatedTokens = ctx == null ? null : ctx.estimatedPromptTokens();

    // 1) Data residency / sensitivity — never expand beyond allowlist; only filter.
    working = applyResidencyFilter(working, sensitivity, reasons, dispositions);

    if (working.isEmpty()) {
      // Do not expand privileges and do not violate residency: fail closed.
      throw new ResidencyConstraintException(
          "no Agent candidate satisfies data residency allowlist for sensitive data");
    }

    // 2) Difficulty preference — promote matching candidate if present in allowlist.
    working =
        promotePreferred(
            working,
            preferredForDifficulty(difficulty),
            RoutingReasonCode.DIFFICULTY,
            reasons,
            dispositions);

    // 3) Latency preference.
    int latencyBudget =
        ctxLatency != null && ctxLatency > 0 ? ctxLatency : properties.getMaxLatencyMs();
    if (latencyBudget > 0) {
      ProviderModelRef lat = properties.getLatencyModel();
      working =
          promotePreferred(
              working,
              lat == null || lat.isBlank()
                  ? null
                  : new RoutingCandidate(lat.getProvider(), lat.getModel()),
              RoutingReasonCode.LATENCY,
              reasons,
              dispositions);
      reasons.add(
          new RoutingReason(RoutingReasonCode.LATENCY, "latency budget ms=" + latencyBudget));
    }

    // 4) Budget signal from #476 ledger (degrade preference within allowlist).
    working = applyBudgetSignal(profile, working, reasons, dispositions);

    // 5) Cost-aware prefer-cheapest / estimated ceiling using pricing (#476).
    working = applyCostStrategy(working, estimatedTokens, reasons, dispositions);

    if (reasons.isEmpty()) {
      reasons.add(
          new RoutingReason(
              RoutingReasonCode.DEFAULT, "profile primary and declared fallback order"));
    }

    RoutingCandidate selected = working.get(0);
    if (dispositions.stream().noneMatch(d -> "SELECTED".equals(d.status()))) {
      dispositions.add(
          0,
          CandidateDisposition.selected(
              selected.provider(), selected.model(), reasons.get(reasons.size() - 1)));
    }
    for (int i = 1; i < working.size(); i++) {
      RoutingCandidate c = working.get(i);
      dispositions.add(
          CandidateDisposition.ordered(
              c.provider(),
              c.model(),
              new RoutingReason(RoutingReasonCode.DEFAULT, "fallback order position " + i)));
    }

    RoutingDecision decision =
        new RoutingDecision(
            UUID.randomUUID().toString(),
            resolveRunId(),
            profile.name(),
            selected.provider(),
            selected.model(),
            reasons,
            dispositions,
            working,
            Instant.now());
    store.append(decision);
    return decision;
  }

  /** Record a 023 fallback switch with an explainable reason (only when routing enabled). */
  public void recordFallback(
      Profile profile,
      String fromProvider,
      String fromModel,
      String toProvider,
      String toModel,
      String cause) {
    if (!properties.isEnabled()) {
      return;
    }
    RoutingReason reason =
        new RoutingReason(
            RoutingReasonCode.FALLBACK,
            "switch "
                + nullToEmpty(fromProvider)
                + "/"
                + nullToEmpty(fromModel)
                + " -> "
                + nullToEmpty(toProvider)
                + "/"
                + nullToEmpty(toModel)
                + ": "
                + nullToEmpty(cause));
    RoutingDecision decision =
        new RoutingDecision(
            UUID.randomUUID().toString(),
            resolveRunId(),
            profile == null ? "" : profile.name(),
            toProvider,
            toModel,
            List.of(reason),
            List.of(
                CandidateDisposition.filtered(
                    fromProvider,
                    fromModel,
                    new RoutingReason(RoutingReasonCode.FALLBACK, "failed: " + nullToEmpty(cause))),
                CandidateDisposition.selected(toProvider, toModel, reason)),
            List.of(new RoutingCandidate(toProvider, toModel)),
            Instant.now());
    store.append(decision);
  }

  public List<RoutingDecision> findByRunId(String runId) {
    return store.findByRunId(runId);
  }

  public List<RoutingDecision> recent(int limit) {
    return store.recent(limit);
  }

  private RoutingDecision passthrough(Profile profile, List<RoutingCandidate> declared) {
    RoutingCandidate selected = declared.get(0);
    return new RoutingDecision(
        null,
        resolveRunId(),
        profile.name(),
        selected.provider(),
        selected.model(),
        List.of(),
        List.of(),
        declared,
        Instant.now());
  }

  static List<RoutingCandidate> declaredCandidates(Profile profile) {
    List<RoutingCandidate> out = new ArrayList<>();
    out.add(new RoutingCandidate(profile.provider().name(), profile.provider().model()));
    profile.provider().fallbacks().forEach(f -> out.add(new RoutingCandidate(f.name(), f.model())));
    return out;
  }

  private List<RoutingCandidate> applyResidencyFilter(
      List<RoutingCandidate> working,
      DataSensitivity sensitivity,
      List<RoutingReason> reasons,
      List<CandidateDisposition> dispositions) {
    List<String> allowed = properties.getResidencyProviders();
    if (sensitivity != DataSensitivity.SENSITIVE || allowed == null || allowed.isEmpty()) {
      return working;
    }
    Set<String> allow =
        allowed.stream()
            .filter(s -> s != null && !s.isBlank())
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    List<RoutingCandidate> kept = new ArrayList<>();
    for (RoutingCandidate c : working) {
      if (allow.contains(c.provider().toLowerCase(Locale.ROOT))) {
        kept.add(c);
      } else {
        dispositions.add(
            CandidateDisposition.filtered(
                c.provider(),
                c.model(),
                new RoutingReason(
                    RoutingReasonCode.SENSITIVITY_RESIDENCY,
                    "provider not in residency allowlist")));
      }
    }
    reasons.add(
        new RoutingReason(
            RoutingReasonCode.SENSITIVITY_RESIDENCY,
            "sensitive data; residency providers=" + allow));
    return kept;
  }

  private List<RoutingCandidate> promotePreferred(
      List<RoutingCandidate> working,
      RoutingCandidate preferred,
      RoutingReasonCode code,
      List<RoutingReason> reasons,
      List<CandidateDisposition> dispositions) {
    if (preferred == null || preferred.provider().isBlank() || preferred.model().isBlank()) {
      return working;
    }
    int idx = indexOf(working, preferred.provider(), preferred.model());
    if (idx < 0) {
      reasons.add(
          new RoutingReason(
              code,
              "preferred "
                  + preferred.provider()
                  + "/"
                  + preferred.model()
                  + " not in Agent allowlist; ignored (no privilege expansion)"));
      return working;
    }
    if (idx == 0) {
      reasons.add(
          new RoutingReason(
              code,
              "preferred " + preferred.provider() + "/" + preferred.model() + " already first"));
      return working;
    }
    List<RoutingCandidate> reordered = new ArrayList<>(working);
    RoutingCandidate moved = reordered.remove(idx);
    reordered.add(0, moved);
    reasons.add(
        new RoutingReason(
            code, "promoted " + moved.provider() + "/" + moved.model() + " to first attempt"));
    dispositions.add(
        CandidateDisposition.selected(
            moved.provider(), moved.model(), new RoutingReason(code, "promoted by policy")));
    return reordered;
  }

  private RoutingCandidate preferredForDifficulty(TaskDifficulty difficulty) {
    if (difficulty == null) {
      return null;
    }
    ProviderModelRef ref =
        properties.getDifficultyModels().get(difficulty.name().toLowerCase(Locale.ROOT));
    if (ref == null) {
      ref = properties.getDifficultyModels().get(difficulty.name());
    }
    if (ref == null || ref.isBlank()) {
      return null;
    }
    return new RoutingCandidate(ref.getProvider(), ref.getModel());
  }

  private List<RoutingCandidate> applyBudgetSignal(
      Profile profile,
      List<RoutingCandidate> working,
      List<RoutingReason> reasons,
      List<CandidateDisposition> dispositions) {
    CostLedgerService ledger = costLedger.get();
    if (ledger == null || !ledger.isEnforcementEnabled()) {
      return working;
    }
    CostContext.State costCtx = CostContext.current();
    String teamId = costCtx == null ? null : costCtx.teamId();
    String runId =
        costCtx != null && costCtx.runId() != null ? costCtx.runId() : TraceContext.current();
    BudgetDecision decision =
        ledger.checkBudget(profile.name(), teamId, working.get(0).model(), runId);
    if (!decision.isDegrade()) {
      return working;
    }
    String dp = decision.degradeProvider();
    String dm = decision.degradeModel();
    if (dp == null || dp.isBlank() || dm == null || dm.isBlank()) {
      reasons.add(
          new RoutingReason(RoutingReasonCode.BUDGET, "budget degrade signaled but target blank"));
      return working;
    }
    reasons.add(
        new RoutingReason(
            RoutingReasonCode.BUDGET,
            decision.reason() == null ? "budget degrade" : decision.reason()));
    return promotePreferred(
        working, new RoutingCandidate(dp, dm), RoutingReasonCode.BUDGET, reasons, dispositions);
  }

  private List<RoutingCandidate> applyCostStrategy(
      List<RoutingCandidate> working,
      Long estimatedPromptTokens,
      List<RoutingReason> reasons,
      List<CandidateDisposition> dispositions) {
    boolean preferCheapest = properties.isPreferCheapest();
    long maxCost = properties.getMaxEstimatedCostMicros();
    if (!preferCheapest && maxCost <= 0) {
      return working;
    }
    if (pricingStore == null) {
      reasons.add(
          new RoutingReason(
              RoutingReasonCode.COST_PREFER_CHEAP,
              "pricing store unavailable; cost strategy skipped"));
      return working;
    }
    long tokens =
        estimatedPromptTokens == null || estimatedPromptTokens <= 0 ? 1000L : estimatedPromptTokens;

    record Scored(RoutingCandidate c, long est) {}
    List<Scored> scored = new ArrayList<>();
    for (RoutingCandidate c : working) {
      Optional<ModelPricing> p = pricingStore.find(c.provider(), c.model());
      long est =
          p.map(
                  mp -> {
                    double prompt = mp.promptPrice() == null ? 0d : mp.promptPrice();
                    return Math.round(tokens * prompt);
                  })
              .orElse(Long.MAX_VALUE / 4);
      if (maxCost > 0 && est > maxCost) {
        dispositions.add(
            CandidateDisposition.filtered(
                c.provider(),
                c.model(),
                new RoutingReason(
                    RoutingReasonCode.COST_PREFER_CHEAP,
                    "estimated cost micros=" + est + " exceeds max=" + maxCost)));
        continue;
      }
      scored.add(new Scored(c, est));
    }
    if (scored.isEmpty()) {
      reasons.add(
          new RoutingReason(
              RoutingReasonCode.COST_PREFER_CHEAP,
              "all candidates over maxEstimatedCostMicros; retaining prior order"));
      return working;
    }
    if (preferCheapest) {
      scored.sort(Comparator.comparingLong(Scored::est));
      reasons.add(
          new RoutingReason(
              RoutingReasonCode.COST_PREFER_CHEAP,
              "prefer cheapest within allowlist; best est micros=" + scored.get(0).est()));
    } else {
      reasons.add(
          new RoutingReason(
              RoutingReasonCode.COST_PREFER_CHEAP,
              "filtered by maxEstimatedCostMicros=" + maxCost));
    }
    List<RoutingCandidate> ordered = new ArrayList<>();
    for (Scored s : scored) {
      ordered.add(s.c());
    }
    if (!ordered.get(0).equals(working.get(0))) {
      dispositions.add(
          CandidateDisposition.selected(
              ordered.get(0).provider(),
              ordered.get(0).model(),
              new RoutingReason(RoutingReasonCode.COST_PREFER_CHEAP, "cost strategy")));
    }
    return ordered;
  }

  private static int indexOf(List<RoutingCandidate> list, String provider, String model) {
    for (int i = 0; i < list.size(); i++) {
      RoutingCandidate c = list.get(i);
      if (c.provider().equals(provider) && c.model().equals(model)) {
        return i;
      }
    }
    return -1;
  }

  private static String resolveRunId() {
    String trace = TraceContext.current();
    if (trace != null && !trace.isBlank()) {
      return trace;
    }
    CostContext.State cost = CostContext.current();
    if (cost != null && cost.runId() != null && !cost.runId().isBlank()) {
      return cost.runId();
    }
    return null;
  }

  private static String nullToEmpty(String v) {
    return v == null ? "" : v;
  }
}
