package io.oryxos.tool.sandbox;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SMTP 出站端点白名单配置（键 {@code smtp.allowed_endpoints}）。条目形如 {@code host} 或 {@code
 * host:port}（端口缺省=任意端口），支持 {@code *.} 通配；空列表 = deny-all。
 */
@ConfigurationProperties(prefix = "smtp")
public record SmtpSandboxProperties(List<String> allowedEndpoints) {

  public SmtpSandboxProperties {
    // null = deny-all；copyOf 固化不可变（SpotBugs EI_EXPOSE）
    allowedEndpoints = allowedEndpoints == null ? List.of() : List.copyOf(allowedEndpoints);
  }
}
