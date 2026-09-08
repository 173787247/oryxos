package io.oryxos.tool.sandbox;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import org.apache.hc.client5.http.DnsResolver;

/** Apache HttpClient 的 DNS 边界：只把 Sandbox 当次校验返回的地址交给 socket 连接层。 */
final class SandboxDnsResolver implements DnsResolver {

  private final ResolvedHttpReadGuard guard;

  SandboxDnsResolver(ResolvedHttpReadGuard guard) {
    this.guard = Objects.requireNonNull(guard, "guard 不能为空");
  }

  @Override
  public InetAddress[] resolve(String host) throws UnknownHostException {
    InetAddress[] addresses = guard.resolveHttpReadHost(host);
    if (addresses == null || addresses.length == 0) {
      throw new UnknownHostException("安全解析未返回可连接地址: " + host);
    }
    return addresses.clone();
  }

  @Override
  public String resolveCanonicalHostname(String host) throws UnknownHostException {
    if (host == null || host.isBlank()) {
      throw new UnknownHostException("主机名为空");
    }
    // 不在 canonical-name 路径做第二次 DNS 查询；原 hostname 继续用于 Host / TLS SNI / 证书校验。
    return host;
  }
}
