package io.oryxos.core.routing;

/** Config binding for a provider/model pair. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification = "Spring ConfigurationProperties binders require mutable getters/setters.")
public class ProviderModelRef {
  private String provider = "";
  private String model = "";

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider == null ? "" : provider;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model == null ? "" : model;
  }

  public boolean isBlank() {
    return provider.isBlank() || model.isBlank();
  }
}
