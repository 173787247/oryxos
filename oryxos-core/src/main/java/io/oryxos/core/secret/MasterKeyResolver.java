package io.oryxos.core.secret;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主密钥两档解析（022，决策树见 data-model.md）：{@code ORYXOS_MASTER_KEY} 环境变量（Base64 的 32 字节）优先； 未设置则用 {@code
 * {oryxosRoot}/master.key} 文件，不存在时首启自动生成（POSIX 0600，仅属主可读）。
 *
 * <p>环境变量与文件同时存在且不一致：环境变量优先、文件忽略——不自动回退另一档（静默换钥匙是排障噩梦，R3）。 格式非法（长度/编码）启动即抛清晰异常。任何日志与异常 message
 * 不携带密钥值（FR-009）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "CRLF_INJECTION_LOGS",
    justification = "日志中唯一动态部分是 oryxos.root 派生的密钥文件路径（部署配置，非用户输入），密钥值从不入日志。")
public final class MasterKeyResolver {

  private static final Logger LOG = LoggerFactory.getLogger(MasterKeyResolver.class);

  /** 生产档环境变量名：值为 Base64 编码的 32 字节随机密钥（openssl rand -base64 32 生成）。 */
  public static final String ENV_VAR = "ORYXOS_MASTER_KEY";

  /** 本地档密钥文件名（位于工作区根，.oryxos/ 已整体 gitignore）。 */
  public static final String KEY_FILE = "master.key";

  private static final int KEY_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Path oryxosRoot;
  private final Supplier<String> envReader;

  /** 生产构造：环境变量走 {@link System#getenv}。 */
  public MasterKeyResolver(Path oryxosRoot) {
    this(oryxosRoot, () -> System.getenv(ENV_VAR));
  }

  /** 可注入 env 读取（单测钉两档优先级用）。 */
  public MasterKeyResolver(Path oryxosRoot, Supplier<String> envReader) {
    this.oryxosRoot = oryxosRoot;
    this.envReader = envReader;
  }

  /** 解析主密钥（32 字节）；见类注释的两档语义。 */
  public byte[] resolve() {
    String fromEnv = envReader.get();
    if (fromEnv != null && !fromEnv.isBlank()) {
      byte[] key = decode(fromEnv.trim(), ENV_VAR + " 环境变量");
      LOG.info("主密钥来源：{} 环境变量（生产档）", ENV_VAR);
      return key;
    }
    return fromFile();
  }

  private byte[] fromFile() {
    Path keyFile = oryxosRoot.resolve(KEY_FILE);
    try {
      if (Files.exists(keyFile)) {
        return decode(Files.readString(keyFile, StandardCharsets.UTF_8).trim(), "密钥文件 " + KEY_FILE);
      }
      byte[] key = new byte[KEY_BYTES];
      RANDOM.nextBytes(key);
      Files.createDirectories(oryxosRoot);
      Files.writeString(keyFile, Base64.getEncoder().encodeToString(key), StandardCharsets.UTF_8);
      restrictToOwner(keyFile);
      LOG.info("主密钥来源：首次启动已自动生成 {}（仅属主可读，本地档）", keyFile);
      return key;
    } catch (IOException e) {
      throw new IllegalStateException("主密钥文件读写失败: " + keyFile, e);
    }
  }

  /** Base64 解码并校验 32 字节；message 只描述格式要求，不回显值（FR-009）。 */
  private static byte[] decode(String base64, String source) {
    byte[] key;
    try {
      key = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          source + " 不是合法 Base64。要求：Base64 编码的 32 字节随机密钥，可用 openssl rand -base64 32 生成");
    }
    if (key.length != KEY_BYTES) {
      throw new IllegalStateException(
          source + " 解码后应为 " + KEY_BYTES + " 字节。可用 openssl rand -base64 32 生成");
    }
    return key;
  }

  /** POSIX 0600；非 POSIX 文件系统（如 Windows）降级跳过，不阻断启动。 */
  private static void restrictToOwner(Path keyFile) throws IOException {
    try {
      Files.setPosixFilePermissions(
          keyFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException e) {
      LOG.warn("当前文件系统不支持 POSIX 权限，master.key 未收紧到 0600（请自行保护该文件）");
    }
  }
}
