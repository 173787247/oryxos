package io.oryxos.core.mcp;

import java.time.Duration;
import java.util.Map;

/**
 * 一个外部 MCP server 的连接配置（{@code .oryxos/mcp_servers.yaml} 条目）。定义在 core：Web 层按依赖倒置只认这个契约，具体连接
 * 实现（stdio 子进程 / 远程 http）留给 oryxos-tool。
 *
 * <p>{@code transport} 目前支持 {@code stdio}（本地子进程，用 {@code command}/{@code env}）与 {@code http}（远程
 * server，用 {@code url}/{@code headers}）；其余值一律跳过并 WARN（未知传输不拖垮启动）。{@code headers} 同 {@code env} 支持
 * {@code ${ENV}} 占位——鉴权 token 走环境变量，不明文落盘（宪法：敏感配置走环境变量）。{@code requestTimeoutSeconds} 为单 server
 * 请求超时，缺省 30 秒。
 */
public record McpServerConfig(
    String name,
    String transport,
    String command,
    Map<String, String> env,
    String url,
    Map<String, String> headers,
    int requestTimeoutSeconds) {

  public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 30;
  public static final int MIN_REQUEST_TIMEOUT_SECONDS = 1;
  public static final int MAX_REQUEST_TIMEOUT_SECONDS = 3600;

  public McpServerConfig {
    env = env == null ? Map.of() : Map.copyOf(env);
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    if (requestTimeoutSeconds < MIN_REQUEST_TIMEOUT_SECONDS
        || requestTimeoutSeconds > MAX_REQUEST_TIMEOUT_SECONDS) {
      throw new IllegalArgumentException(
          "MCP request_timeout 必须在 "
              + MIN_REQUEST_TIMEOUT_SECONDS
              + ".."
              + MAX_REQUEST_TIMEOUT_SECONDS
              + " 秒之间: "
              + requestTimeoutSeconds);
    }
  }

  /** 兼容未配置 timeout 的既有调用方与配置，保持原有 30 秒行为。 */
  public McpServerConfig(
      String name,
      String transport,
      String command,
      Map<String, String> env,
      String url,
      Map<String, String> headers) {
    this(name, transport, command, env, url, headers, DEFAULT_REQUEST_TIMEOUT_SECONDS);
  }

  public Duration requestTimeout() {
    return Duration.ofSeconds(requestTimeoutSeconds);
  }

  public static final String TRANSPORT_STDIO = "stdio";
  public static final String TRANSPORT_HTTP = "http";
}
