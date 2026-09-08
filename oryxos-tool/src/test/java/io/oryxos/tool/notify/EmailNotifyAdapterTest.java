package io.oryxos.tool.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import io.oryxos.tool.sandbox.FileSandboxProperties;
import io.oryxos.tool.sandbox.HttpSandboxProperties;
import io.oryxos.tool.sandbox.PermissiveSandbox;
import io.oryxos.tool.sandbox.SandboxViolationException;
import io.oryxos.tool.sandbox.ShellSandboxProperties;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import jakarta.mail.Message;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * 课件《第19节》扩展阶段 SMTP 专用 Adapter：EmailNotifyAdapterTest。
 *
 * <p>不接真 SMTP 服务器（沿用课件"假服务用本地桩"思路）：正常路径用 Mockito 5 内联 mockmaker {@code mockStatic(Transport.class)}
 * 拦下静态 {@code Transport.send(Message)}，断言消息与 Session 属性组装正确；沙箱拦截用真 {@link WhitelistSandbox}（SMTP
 * 白名单空 → deny-all）。
 */
class EmailNotifyAdapterTest {

  private static final Map<String, String> BASE_CONFIG =
      Map.of(
          "host", "smtp.example.com",
          "port", "587",
          "from", "ops@corp.com",
          "to", "admin@corp.com");

  private static NotifyTarget target(Map<String, String> overrides) {
    Map<String, String> config = new HashMap<>(BASE_CONFIG);
    config.putAll(overrides);
    return new NotifyTarget("email", config);
  }

  private static WhitelistSandbox emptySmtpSandbox() {
    return new WhitelistSandbox(
        new FileSandboxProperties(List.of()),
        new ShellSandboxProperties(List.of()),
        new HttpSandboxProperties(List.of()));
  }

  @Test
  @DisplayName("正常路径：Transport.send 恰好一次，发件人/收件人/主题/正文正确")
  void sendBuildsAndDeliversMessage() throws Exception {
    EmailNotifyAdapter adapter = new EmailNotifyAdapter(new PermissiveSandbox());

    try (MockedStatic<Transport> transport = mockStatic(Transport.class)) {
      adapter.send(target(Map.of("subject", "告警")), "磁盘 90%");
      ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
      transport.verify(() -> Transport.send(captor.capture()), times(1));

      MimeMessage message = captor.getValue();
      assertEquals("ops@corp.com", ((InternetAddress) message.getFrom()[0]).getAddress(), "发件人");
      assertEquals(
          "admin@corp.com",
          ((InternetAddress) message.getRecipients(Message.RecipientType.TO)[0]).getAddress(),
          "收件人");
      assertEquals("告警", message.getSubject());
      assertEquals("磁盘 90%", message.getContent());
    }
  }

  @Test
  @DisplayName("Session 属性：host/port 映射，587 默认 starttls")
  void sessionPropertiesMapHostPortEncryption() throws Exception {
    EmailNotifyAdapter adapter = new EmailNotifyAdapter(new PermissiveSandbox());

    try (MockedStatic<Transport> transport = mockStatic(Transport.class)) {
      adapter.send(target(Map.of()), "hello");
      ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
      transport.verify(() -> Transport.send(captor.capture()));

      Properties props = captor.getValue().getSession().getProperties();
      assertEquals("smtp.example.com", props.getProperty("mail.smtp.host"));
      assertEquals("587", props.getProperty("mail.smtp.port"));
      assertEquals("true", props.getProperty("mail.smtp.starttls.enable"));
    }
  }

  @Test
  @DisplayName("Session 属性：SMTP 建连/读/写三段超时已设置（Jakarta Mail 缺省无限阻塞会永久卡住 ReAct 轮）")
  void sessionPropertiesIncludeTimeouts() throws Exception {
    EmailNotifyAdapter adapter = new EmailNotifyAdapter(new PermissiveSandbox());

    try (MockedStatic<Transport> transport = mockStatic(Transport.class)) {
      adapter.send(target(Map.of()), "hello");
      ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
      transport.verify(() -> Transport.send(captor.capture()));

      Properties props = captor.getValue().getSession().getProperties();
      assertEquals("10000", props.getProperty("mail.smtp.connectiontimeout"));
      assertEquals("10000", props.getProperty("mail.smtp.timeout"));
      assertEquals("10000", props.getProperty("mail.smtp.writetimeout"));
    }
  }

  @Test
  @DisplayName("认证：配置 username 时显式开启 mail.smtp.auth（否则不发 AUTH → 530 未认证）")
  void authEnabledWhenUsernamePresent() throws Exception {
    EmailNotifyAdapter adapter = new EmailNotifyAdapter(new PermissiveSandbox());

    try (MockedStatic<Transport> transport = mockStatic(Transport.class)) {
      adapter.send(target(Map.of("username", "ops", "password", "s3cret")), "hello");
      ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
      transport.verify(() -> Transport.send(captor.capture()));

      Properties props = captor.getValue().getSession().getProperties();
      assertEquals("true", props.getProperty("mail.smtp.auth"));
    }
  }

  @Test
  @DisplayName("encryption 推断：465→ssl、其余→none，显式值优先")
  void encryptionInferredFromPort() throws Exception {
    EmailNotifyAdapter adapter = new EmailNotifyAdapter(new PermissiveSandbox());

    // 465 → ssl
    try (MockedStatic<Transport> transport = mockStatic(Transport.class)) {
      adapter.send(target(Map.of("port", "465")), "hello");
      ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
      transport.verify(() -> Transport.send(captor.capture()));
      Properties props = captor.getValue().getSession().getProperties();
      assertEquals("true", props.getProperty("mail.smtp.ssl.enable"));
    }

    // 25 → none（不设 ssl/starttls）
    try (MockedStatic<Transport> transport = mockStatic(Transport.class)) {
      adapter.send(target(Map.of("port", "25")), "hello");
      ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
      transport.verify(() -> Transport.send(captor.capture()));
      Properties props = captor.getValue().getSession().getProperties();
      assertNull(props.getProperty("mail.smtp.ssl.enable"));
      assertNull(props.getProperty("mail.smtp.starttls.enable"));
    }

    // 显式值优先：端口 25 + encryption=ssl → ssl
    try (MockedStatic<Transport> transport = mockStatic(Transport.class)) {
      adapter.send(target(Map.of("port", "25", "encryption", "ssl")), "hello");
      ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
      transport.verify(() -> Transport.send(captor.capture()));
      Properties props = captor.getValue().getSession().getProperties();
      assertEquals("true", props.getProperty("mail.smtp.ssl.enable"));
    }
  }

  @Test
  @DisplayName("白名单拦截：SMTP 白名单空，抛 SandboxViolationException 且未触达 Transport")
  void unlistedEndpointBlockedBeforeSend() {
    EmailNotifyAdapter adapter = new EmailNotifyAdapter(emptySmtpSandbox());

    try (MockedStatic<Transport> transport = mockStatic(Transport.class)) {
      assertThrows(SandboxViolationException.class, () -> adapter.send(target(Map.of()), "leaked"));
      transport.verifyNoInteractions();
    }
  }

  @Test
  @DisplayName("缺必填键 host/port/from/to：报错点名且未触达 Transport")
  void missingRequiredKeyFailsFast() {
    EmailNotifyAdapter adapter = new EmailNotifyAdapter(new PermissiveSandbox());

    for (String key : List.of("host", "port", "from", "to")) {
      Map<String, String> config = new HashMap<>(BASE_CONFIG);
      config.remove(key);
      try (MockedStatic<Transport> transport = mockStatic(Transport.class)) {
        IllegalArgumentException ex =
            assertThrows(
                IllegalArgumentException.class,
                () -> adapter.send(new NotifyTarget("email", config), "hello"));
        assertTrue(ex.getMessage().contains("缺少配置键 " + key), "报错点名缺失键 " + key);
        transport.verifyNoInteractions();
      }
    }
  }

  @Test
  @DisplayName("encryption 非法值报错，不静默明文")
  void invalidEncryptionRejected() {
    EmailNotifyAdapter adapter = new EmailNotifyAdapter(new PermissiveSandbox());

    try (MockedStatic<Transport> transport = mockStatic(Transport.class)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> adapter.send(target(Map.of("encryption", "tls1.3")), "hello"));
      transport.verifyNoInteractions();
    }
  }

  @Test
  @DisplayName("凭证占位：${VAR} 解析成环境变量，字面量原样透传")
  void envPlaceholderResolves() {
    assertEquals("literal-secret", EmailNotifyAdapter.resolveEnv("literal-secret"));
    assertNull(EmailNotifyAdapter.resolveEnv(null));
    assertNull(EmailNotifyAdapter.resolveEnv("${ORYXOS_UNSET_VAR_9f2c}"));
    if (!System.getenv().isEmpty()) {
      String var = System.getenv().keySet().iterator().next();
      assertEquals(System.getenv(var), EmailNotifyAdapter.resolveEnv("${" + var + "}"));
    }
  }
}
