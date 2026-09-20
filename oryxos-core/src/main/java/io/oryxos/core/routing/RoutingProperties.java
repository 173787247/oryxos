package io.oryxos.core.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Explainable model routing flags (#477). Defaults keep runtime unchanged.
 *
 * <pre>
 * oryxos.routing.enabled=false
 * </pre>
 *
 * Flip {@code enabled} for canary; set false to roll back.
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification = "Spring ConfigurationProperties binders require mutable getters/setters.")
@ConfigurationProperties(prefix = "oryxos.routing")
public class RoutingProperties {

  /** Master switch for strategy + decision API. Default off (gray-release / rollback). */
  private boolean enabled = false;

  /** When true, prefer cheaper candidates within the Agent allowlist using llm_pricing. */
  private boolean preferCheapest = false;

  /** Soft estimated-cost ceiling (micros); 0 = off. Uses estimatedPromptTokens from context. */
  private long maxEstimatedCostMicros = 0L;

  /** Soft latency preference (ms); 0 = off. Prefer latencyModels when set. */
  private int maxLatencyMs = 0;

  /** difficulty -> preferred provider/model (must match an Agent candidate to take effect). */
  private Map<String, ProviderModelRef> difficultyModels = new HashMap<>();

  /** When latency budget is tight, prefer this provider/model if in candidates. */
  private ProviderModelRef latencyModel = new ProviderModelRef();

  /**
   * Providers allowed when {@link DataSensitivity#SENSITIVE}. Empty = no residency filter (policy
   * off).
   */
  private List<String> residencyProviders = new ArrayList<>();

  /** Ring-buffer size for in-memory decision log (API query). */
  private int decisionLogSize = 256;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isPreferCheapest() {
    return preferCheapest;
  }

  public void setPreferCheapest(boolean preferCheapest) {
    this.preferCheapest = preferCheapest;
  }

  public long getMaxEstimatedCostMicros() {
    return maxEstimatedCostMicros;
  }

  public void setMaxEstimatedCostMicros(long maxEstimatedCostMicros) {
    this.maxEstimatedCostMicros = Math.max(0L, maxEstimatedCostMicros);
  }

  public int getMaxLatencyMs() {
    return maxLatencyMs;
  }

  public void setMaxLatencyMs(int maxLatencyMs) {
    this.maxLatencyMs = Math.max(0, maxLatencyMs);
  }

  public Map<String, ProviderModelRef> getDifficultyModels() {
    return difficultyModels;
  }

  public void setDifficultyModels(Map<String, ProviderModelRef> difficultyModels) {
    this.difficultyModels = difficultyModels == null ? new HashMap<>() : difficultyModels;
  }

  public ProviderModelRef getLatencyModel() {
    return latencyModel;
  }

  public void setLatencyModel(ProviderModelRef latencyModel) {
    this.latencyModel = latencyModel == null ? new ProviderModelRef() : latencyModel;
  }

  public List<String> getResidencyProviders() {
    return residencyProviders;
  }

  public void setResidencyProviders(List<String> residencyProviders) {
    this.residencyProviders = residencyProviders == null ? new ArrayList<>() : residencyProviders;
  }

  public int getDecisionLogSize() {
    return decisionLogSize;
  }

  public void setDecisionLogSize(int decisionLogSize) {
    this.decisionLogSize = Math.max(16, decisionLogSize);
  }
}
