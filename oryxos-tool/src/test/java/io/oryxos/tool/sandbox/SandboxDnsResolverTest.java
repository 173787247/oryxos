package io.oryxos.tool.sandbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SandboxDnsResolverTest {

  @Test
  @DisplayName("DNS resolver 把同一次安全校验返回的地址集交给连接层")
  void returnsDefensiveCopyOfValidatedAddresses() throws Exception {
    InetAddress approved = InetAddress.getByAddress(new byte[] {(byte) 203, 0, 113, 10});
    InetAddress[] approvedSet = {approved};
    AtomicInteger calls = new AtomicInteger();
    ResolvedHttpReadGuard guard =
        host -> {
          assertEquals("safe.example", host);
          calls.incrementAndGet();
          return approvedSet;
        };

    InetAddress[] resolved = new SandboxDnsResolver(guard).resolve("safe.example");

    assertArrayEquals(approvedSet, resolved);
    assertNotSame(approvedSet, resolved);
    assertEquals(1, calls.get());
  }

  @Test
  @DisplayName("空解析结果必须 fail closed")
  void rejectsEmptyValidatedAddressSet() {
    ResolvedHttpReadGuard guard = host -> new InetAddress[0];

    assertThrows(
        UnknownHostException.class, () -> new SandboxDnsResolver(guard).resolve("empty.example"));
  }

  @Test
  @DisplayName("未提供已验证解析能力的 Sandbox 不得打开 HTTP_READ client")
  void clientRejectsSandboxWithoutResolvedReadGuard() {
    Sandbox unsupported = action -> {};

    assertThrows(
        SandboxViolationException.class,
        () -> PinnedHttpReadClient.open(unsupported, Duration.ofSeconds(1), Duration.ofSeconds(1)));
  }

  @Test
  @DisplayName("HTTP_READ client 关闭时释放连接池")
  void closeReleasesConnectionManager() {
    PinnedHttpReadClient client =
        PinnedHttpReadClient.open(
            new PermissiveSandbox(), Duration.ofSeconds(1), Duration.ofSeconds(1));
    assertFalse(client.isClosed());

    client.close();

    assertTrue(client.isClosed());
  }
}
