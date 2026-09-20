package io.oryxos.core.flow;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Flow engine flags (046 / #468 + 047 / #469): {@code oryxos.flow.*}. Defaults keep Agent/boot
 * behavior unchanged.
 */
@ConfigurationProperties(prefix = "oryxos.flow")
public class FlowEngineProperties {

  /** Master switch. Default off — zero Agent/boot behavior change. */
  private boolean engineEnabled = false;

  /** Max node retries on FAILED before failing the run (0 = no retry). */
  private int defaultMaxRetries = 0;

  /**
   * When true, failed nodes with a declared {@code compensate} target run that node once before the
   * run ends FAILED. Default off (#469).
   */
  private boolean compensationEnabled = false;

  public boolean isEngineEnabled() {
    return engineEnabled;
  }

  public void setEngineEnabled(boolean engineEnabled) {
    this.engineEnabled = engineEnabled;
  }

  public int getDefaultMaxRetries() {
    return defaultMaxRetries;
  }

  public void setDefaultMaxRetries(int defaultMaxRetries) {
    this.defaultMaxRetries = Math.max(0, defaultMaxRetries);
  }

  public boolean isCompensationEnabled() {
    return compensationEnabled;
  }

  public void setCompensationEnabled(boolean compensationEnabled) {
    this.compensationEnabled = compensationEnabled;
  }
}
