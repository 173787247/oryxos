package io.oryxos.cli;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OTel trace 导出配置（039，oryxos.otel.*）：endpoint 为空（默认）= 不初始化 SDK、装配 NOOP—— 零依赖零连接零导出尝试（FR-008）。非空时启用
 * OTLP gRPC 批量导出。
 */
@ConfigurationProperties(prefix = "oryxos.otel")
public class OtelProperties {

  /** OTLP gRPC 端点（如 http://jaeger:4317）；空 = 禁用。 */
  private String endpoint = "";

  /** 采样比 0.0~1.0；默认全采（企业内网流量规模）。 */
  private double samplerRatio = 1.0;

  public boolean enabled() {
    return endpoint != null && !endpoint.isBlank();
  }

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }

  public double getSamplerRatio() {
    return samplerRatio;
  }

  public void setSamplerRatio(double samplerRatio) {
    this.samplerRatio = samplerRatio;
  }
}
