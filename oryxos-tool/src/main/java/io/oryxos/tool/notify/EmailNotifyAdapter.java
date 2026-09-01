package io.oryxos.tool.notify;

import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;
import java.util.Properties;

/**
 * 邮件通知渠道：把一条内容作为邮件正文发出（第 5 个 {@link NotifyChannelAdapter}，第 19 节预留的「扩展阶段 SMTP 专用 Adapter」）。
 *
 * <p>配置可走两条路径：内联 Profile 的 {@code notify_channels}（{@code type: email}），或全局注册表 {@code
 * NotifyChannelDef} 的 {@code config}（多字段）。凭证（password/username）支持 {@code ${VAR}} 环境变量占位，由本 adapter
 * 在发送时解析，绝不落明文（宪法明文）；内联路径另经 {@code ProfileLoader} 先行解析，两者兼容。
 *
 * <p>出站先过沙箱 {@link ActionType#SMTP_SEND}（按 host:port 精确放行，区别于 HTTP 只校验域名）；SMTP 用 Jakarta Mail 同步阻塞
 * 发送，契合宪法 VII（virtual thread 扛 IO）。
 */
public final class EmailNotifyAdapter implements NotifyChannelAdapter {

  private static final String KEY_HOST = "host";
  private static final String KEY_PORT = "port";
  private static final String KEY_FROM = "from";
  private static final String KEY_TO = "to";
  private static final String KEY_USERNAME = "username";
  private static final String KEY_PASSWORD = "password";
  private static final String KEY_SUBJECT = "subject";
  private static final String KEY_ENCRYPTION = "encryption";

  private static final String DEFAULT_SUBJECT = "OryxOS 通知";

  private static final String ENCRYPTION_SSL = "ssl";
  private static final String ENCRYPTION_STARTTLS = "starttls";
  private static final String ENCRYPTION_NONE = "none";

  private static final int MAX_PORT = 65535;
  private static final int DEFAULT_SSL_PORT = 465;
  private static final int DEFAULT_STARTTLS_PORT = 587;

  /**
   * SMTP 三段超时（建连/读/写）：Jakarta Mail 缺省无限阻塞，同步执行模型（宪法 VII）下挂死的 SMTP 端点会把该 virtual thread 连同整个 ReAct
   * 轮永久卡住——与 HttpTools/MCP 的超时口径对齐。
   */
  private static final String SMTP_TIMEOUT_MS = "10000";

  /** 凭证环境变量占位的包裹符：{@code ${VAR}} → 读取环境变量 VAR。 */
  private static final String ENV_PREFIX = "${";

  private static final String ENV_SUFFIX = "}";

  private final Sandbox sandbox;

  public EmailNotifyAdapter(Sandbox sandbox) {
    this.sandbox = sandbox;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    Map<String, String> config = target.config();
    String host = require(config, KEY_HOST);
    int port = parsePort(require(config, KEY_PORT));
    String from = require(config, KEY_FROM);
    String to = require(config, KEY_TO);
    String username = resolveEnv(config.get(KEY_USERNAME));
    String password = resolveEnv(config.get(KEY_PASSWORD));
    String subject = config.getOrDefault(KEY_SUBJECT, DEFAULT_SUBJECT);
    String encryption = resolveEncryption(config.get(KEY_ENCRYPTION), port);

    // 涉外 IO 前过沙箱（宪法 VI / H4 不变量①）：SMTP 出站按 host:port 精确放行
    sandbox.enforce(new SandboxAction(ActionType.SMTP_SEND, host + ":" + port));

    Properties props = new Properties();
    props.put("mail.smtp.host", host);
    props.put("mail.smtp.port", String.valueOf(port));
    props.put("mail.smtp.connectiontimeout", SMTP_TIMEOUT_MS);
    props.put("mail.smtp.timeout", SMTP_TIMEOUT_MS);
    props.put("mail.smtp.writetimeout", SMTP_TIMEOUT_MS);
    if (ENCRYPTION_SSL.equals(encryption)) {
      props.put("mail.smtp.ssl.enable", "true");
    } else if (ENCRYPTION_STARTTLS.equals(encryption)) {
      props.put("mail.smtp.starttls.enable", "true");
    }
    boolean auth = username != null && !username.isBlank();
    if (auth) {
      // 认证必需显式打开 mail.smtp.auth：缺省 false 时 Transport.send 不发 AUTH 命令，服务器回 530「Client was not
      // authenticated」。
      props.put("mail.smtp.auth", "true");
    }
    Session session =
        auth
            ? Session.getInstance(
                props, new SmtpAuthenticator(username, password == null ? "" : password))
            : Session.getInstance(props);

    try {
      MimeMessage message = new MimeMessage(session);
      message.setFrom(new InternetAddress(from));
      message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
      message.setSubject(subject, "UTF-8");
      message.setText(content, "UTF-8");
      Transport.send(message);
    } catch (MessagingException e) {
      throw new IllegalStateException("邮件发送失败: " + e.getMessage(), e);
    }
  }

  private static String require(Map<String, String> config, String key) {
    String value = config.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          "email 渠道缺少配置键 " + key + "（notify_channels 的 email 条目需要 host/port/from/to）");
    }
    return value.strip();
  }

  private static int parsePort(String raw) {
    int port;
    try {
      port = Integer.parseInt(raw.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("email 渠道 port 不是整数: " + raw);
    }
    if (port < 1 || port > MAX_PORT) {
      throw new IllegalArgumentException("email 渠道 port 非法（须 1~" + MAX_PORT + "）: " + raw);
    }
    return port;
  }

  /** 解析 {@code ${VAR}} 环境变量占位（凭证走环境变量，注册表 config 不落明文）；非占位值原样返回。 */
  static String resolveEnv(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    if (trimmed.startsWith(ENV_PREFIX) && trimmed.endsWith(ENV_SUFFIX)) {
      return System.getenv(
          trimmed.substring(ENV_PREFIX.length(), trimmed.length() - ENV_SUFFIX.length()));
    }
    return value;
  }

  /** 缺省按端口推断加密（465→ssl、587→starttls、其余→none）；显式值优先，非法值报错（不静默明文）。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "encryption 是受限 ASCII 令牌（ssl/starttls/none），大小写不敏感比对是安全且意图明确的；不涉及会因 unicode 折叠改变语义的字段")
  private static String resolveEncryption(String explicit, int port) {
    if (explicit == null || explicit.isBlank()) {
      if (port == DEFAULT_SSL_PORT) {
        return ENCRYPTION_SSL;
      }
      if (port == DEFAULT_STARTTLS_PORT) {
        return ENCRYPTION_STARTTLS;
      }
      return ENCRYPTION_NONE;
    }
    String value = explicit.strip();
    if (ENCRYPTION_SSL.equalsIgnoreCase(value)) {
      return ENCRYPTION_SSL;
    }
    if (ENCRYPTION_STARTTLS.equalsIgnoreCase(value)) {
      return ENCRYPTION_STARTTLS;
    }
    if (ENCRYPTION_NONE.equalsIgnoreCase(value)) {
      return ENCRYPTION_NONE;
    }
    throw new IllegalArgumentException(
        "email 渠道 encryption 非法: " + explicit + "（可选 ssl / starttls / none）");
  }

  /** SMTP 认证凭证：只在配置了 username 时启用；密码缺省传空串（某些服务器允许空口令）。 */
  private static final class SmtpAuthenticator extends Authenticator {

    private final String username;
    private final String password;

    SmtpAuthenticator(String username, String password) {
      this.username = username;
      this.password = password;
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
      return new PasswordAuthentication(username, password);
    }
  }
}
