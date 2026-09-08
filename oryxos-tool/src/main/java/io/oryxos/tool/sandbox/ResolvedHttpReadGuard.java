package io.oryxos.tool.sandbox;

import java.net.InetAddress;

/**
 * HTTP 读请求的连接时安全解析能力。
 *
 * <p>实现必须在同一次调用中解析主机名、校验全部候选地址，并返回传输层唯一允许使用的地址集。这样校验与建连不会分别观察到不同的 DNS 结果。
 */
@FunctionalInterface
public interface ResolvedHttpReadGuard {

  /** 返回已通过当前 Sandbox 策略校验、可供当次 HTTP_READ 连接使用的地址。 */
  InetAddress[] resolveHttpReadHost(String host);
}
