package io.oryxos.tool.sandbox;

import java.time.Duration;
import java.util.Objects;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 一个 HTTP_READ hop 的短生命安全 client：禁自动重定向，socket 只使用 Sandbox 当次校验过的 DNS 地址集。
 *
 * <p>调用方必须使用 try-with-resources；每个 hop 独立的连接池不会跨跳复用旧地址集。
 */
public final class PinnedHttpReadClient implements AutoCloseable {

  private final PoolingHttpClientConnectionManager connectionManager;
  private final CloseableHttpClient httpClient;
  private final RestClient restClient;

  private PinnedHttpReadClient(
      PoolingHttpClientConnectionManager connectionManager,
      CloseableHttpClient httpClient,
      RestClient restClient) {
    this.connectionManager = connectionManager;
    this.httpClient = httpClient;
    this.restClient = restClient;
  }

  public static PinnedHttpReadClient open(
      Sandbox sandbox, Duration connectTimeout, Duration readTimeout) {
    Objects.requireNonNull(sandbox, "sandbox 不能为空");
    Objects.requireNonNull(connectTimeout, "connectTimeout 不能为空");
    Objects.requireNonNull(readTimeout, "readTimeout 不能为空");
    if (!(sandbox instanceof ResolvedHttpReadGuard guard)) {
      throw new SandboxViolationException("Sandbox 未提供与连接绑定的 HTTP_READ 安全解析能力");
    }

    ConnectionConfig connectionConfig =
        ConnectionConfig.custom()
            .setConnectTimeout(Timeout.of(connectTimeout))
            .setSocketTimeout(Timeout.of(readTimeout))
            .build();
    PoolingHttpClientConnectionManager connectionManager =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(new SandboxDnsResolver(guard))
            .setDefaultConnectionConfig(connectionConfig)
            .build();
    CloseableHttpClient httpClient =
        HttpClients.custom()
            .setConnectionManager(connectionManager)
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .build();
    HttpComponentsClientHttpRequestFactory requestFactory =
        new HttpComponentsClientHttpRequestFactory(httpClient);
    requestFactory.setConnectionRequestTimeout(connectTimeout);
    requestFactory.setReadTimeout(readTimeout);
    RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
    return new PinnedHttpReadClient(connectionManager, httpClient, restClient);
  }

  public RestClient restClient() {
    return restClient;
  }

  boolean isClosed() {
    return connectionManager.isClosed();
  }

  @Override
  public void close() {
    httpClient.close(CloseMode.GRACEFUL);
  }
}
