package io.oryxos.core.a2a;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code oryxos.a2a.*} — A2A discovery + message/send (Direction C / I). Default off. */
@ConfigurationProperties(prefix = "oryxos.a2a")
public record A2aProperties(
    boolean enabled,
    @DefaultValue("OryxOS") String name,
    @DefaultValue("OryxOS Agent Harness — local agents advertised for A2A discovery")
        String description,
    @DefaultValue("http://localhost:8080") String publicBaseUrl,
    @DefaultValue("0.1.6-RELEASE") String version,
    @DefaultValue("0.3.0") String protocolVersion,
    /** Default local agent when params.metadata.agent is omitted. */
    @DefaultValue("") String defaultAgent) {

  public A2aProperties {
    name = name == null || name.isBlank() ? "OryxOS" : name.strip();
    description =
        description == null || description.isBlank()
            ? "OryxOS Agent Harness — local agents advertised for A2A discovery"
            : description.strip();
    publicBaseUrl =
        publicBaseUrl == null || publicBaseUrl.isBlank()
            ? "http://localhost:8080"
            : publicBaseUrl.strip().replaceAll("/+$", "");
    version = version == null || version.isBlank() ? "0.1.6-RELEASE" : version.strip();
    protocolVersion =
        protocolVersion == null || protocolVersion.isBlank() ? "0.3.0" : protocolVersion.strip();
    defaultAgent = defaultAgent == null ? "" : defaultAgent.strip();
  }

  public static A2aProperties disabled() {
    return new A2aProperties(
        false,
        "OryxOS",
        "OryxOS Agent Harness — local agents advertised for A2A discovery",
        "http://localhost:8080",
        "0.1.6-RELEASE",
        "0.3.0",
        "");
  }

  public String a2aServiceUrl() {
    return publicBaseUrl + "/api/v1/a2a";
  }
}
