package io.oryxos.tool.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSH execution backend settings ({@code oryxos.sandbox.execution.ssh.*}). Used when {@code
 * oryxos.sandbox.execution.backend=ssh}. Default-off: empty host means ssh is not configured.
 */
@ConfigurationProperties(prefix = "oryxos.sandbox.execution.ssh")
public record SshExecutionProperties(String host, String user, int port, String identityFile) {

  public SshExecutionProperties {
    host = host == null ? "" : host.trim();
    user = user == null || user.isBlank() ? "oryxos" : user.trim();
    port = port <= 0 ? 22 : port;
    identityFile = identityFile == null ? "" : identityFile.trim();
  }

  public boolean configured() {
    return !host.isBlank();
  }
}
