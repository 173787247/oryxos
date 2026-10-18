package io.oryxos.core.secret;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主密钥两档解析（022，决策树见 data-model.md）：{@code ORYXOS_MASTER_KEY} 环境变量（Base64 的 32 字节）优先； 未设置则用 {@code
 * {oryxosRoot}/master.key} 文件，不存在时本次启动临时生成。
 *
 * <p>「临时生成」不落盘：{@link #resolveForStartup()} 只把新钥匙放进内存，{@link #persistIfGenerated()} 才写文件，
 * 且装配层把它排在密钥守卫 <b>通过之后</b>。反过来的顺序——先写文件、再发现它解不开库里的密文——会在拒启提示里把运维指向一个确实存在、 却解不开任何东西的文件：
 * 「找回原密钥」于是退化成「找回那个刚生成的文件」。
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
  private static final Set<StandardOpenOption> CREATE_TRUNCATE_WRITE =
      Set.of(
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);

  private final Path oryxosRoot;
  private final Supplier<String> envReader;

  /** 启动期解析结果缓存：cipher 与 migration 两个装配点必须看到同一把钥匙，尤其是临时生成的那把。 */
  private ResolvedMasterKey resolved;

  /** 生产构造：环境变量走 {@link System#getenv}。 */
  public MasterKeyResolver(Path oryxosRoot) {
    this(oryxosRoot, () -> System.getenv(ENV_VAR));
  }

  /** 可注入 env 读取（单测钉两档优先级用）。 */
  public MasterKeyResolver(Path oryxosRoot, Supplier<String> envReader) {
    this.oryxosRoot = oryxosRoot;
    this.envReader = envReader;
  }

  /** 密钥来源：决定拒启文案（该叫谁去找钥匙）以及是否需要落盘。 */
  public enum Origin {
    /** {@code ORYXOS_MASTER_KEY} 环境变量（生产档）。 */
    ENV,
    /** 已存在的密钥文件。 */
    FILE,
    /** 本次启动临时生成，尚未落盘；是否写入由 {@link #persistIfGenerated()} 决定。 */
    GENERATED
  }

  /**
   * 启动期解析结果。{@link Origin#GENERATED} 表示密钥只在内存里——在 {@link #persistIfGenerated()} 之前它没有写过磁盘。
   *
   * <p>key 做防御性拷贝：内部数组不是调用方传进来的那个，调用方也拿不到内部数组。
   */
  public record ResolvedMasterKey(byte[] key, Origin origin) {

    public ResolvedMasterKey {
      key = key == null ? null : key.clone();
    }

    @Override
    public byte[] key() {
      return key == null ? null : key.clone();
    }
  }

  /**
   * 启动期解析主密钥（只读：env → 文件；都没有则临时生成，<b>不落盘</b>）。
   *
   * <p>同一实例只解析一次：装配层里 cipher 与 migration 两个 bean 必须用同一把钥匙，重新解析会让临时生成的那把对不上。
   */
  public synchronized ResolvedMasterKey resolveForStartup() {
    if (resolved == null) {
      resolved = doResolve();
    }
    return resolved;
  }

  /**
   * 守卫通过后落盘：只对本次临时生成的钥匙生效（env / 已存在文件都无需写）。可重复调用，非生成来源直接返回。
   *
   * <p>调用时机是契约的一部分：必须先经过密钥守卫——守卫要确认的正是「库里已有的密文能被解开的这把钥匙」； 先落盘再守卫，拒启时盘上就多了一把错钥匙。
   */
  public synchronized void persistIfGenerated() {
    ResolvedMasterKey current = resolved;
    if (current == null || current.origin() != Origin.GENERATED) {
      return;
    }
    Path keyFile = keyFile();
    try {
      Files.createDirectories(oryxosRoot);
      writeKeyFile(keyFile, current.key());
      restrictToOwner(keyFile);
      LOG.info("主密钥来源：首次启动已自动生成 {}（仅属主可读，本地档）", keyFile);
    } catch (IOException e) {
      throw new IllegalStateException("主密钥文件写入失败: " + keyFile, e);
    }
  }

  /** 解析主密钥（32 字节）并确保临时生成的钥匙落盘；见类注释的两档语义。 */
  public byte[] resolve() {
    ResolvedMasterKey current = resolveForStartup();
    persistIfGenerated();
    return current.key();
  }

  /** 密钥文件路径（本地档）；拒启文案要用它指路。 */
  public Path keyFile() {
    return oryxosRoot.resolve(KEY_FILE);
  }

  private ResolvedMasterKey doResolve() {
    String fromEnv = envReader.get();
    if (fromEnv != null && !fromEnv.isBlank()) {
      byte[] key = decode(fromEnv.trim(), ENV_VAR + " 环境变量");
      LOG.info("主密钥来源：{} 环境变量（生产档）", ENV_VAR);
      return new ResolvedMasterKey(key, Origin.ENV);
    }
    Path keyFile = keyFile();
    try {
      if (Files.exists(keyFile)) {
        byte[] key =
            decode(Files.readString(keyFile, StandardCharsets.UTF_8).trim(), "密钥文件 " + KEY_FILE);
        // 升级路径：老版本先写内容再 chmod，崩溃/权限失败会留下 0644——读到就归一到 0600
        restrictToOwner(keyFile);
        LOG.info("主密钥来源：{}", keyFile);
        return new ResolvedMasterKey(key, Origin.FILE);
      }
      byte[] key = new byte[KEY_BYTES];
      RANDOM.nextBytes(key);
      LOG.info("主密钥：{} 不存在且 {} 未设置，本次启动临时生成（守卫通过后才落盘）", keyFile, ENV_VAR);
      return new ResolvedMasterKey(key, Origin.GENERATED);
    } catch (IOException e) {
      throw new IllegalStateException("主密钥文件读写失败: " + keyFile, e);
    }
  }

  /**
   * 写密钥文件：创建即 0600（POSIX 属性在创建时生效），不存在「先按 umask 建出来、再 chmod」的可读窗口。
   *
   * <p>非 POSIX 文件系统（Windows）不接受 POSIX 属性，回退为普通写入，由 {@link #restrictToOwner} 记一条降级 WARN。
   */
  private static void writeKeyFile(Path keyFile, byte[] key) throws IOException {
    byte[] content = Base64.getEncoder().encodeToString(key).getBytes(StandardCharsets.UTF_8);
    FileAttribute<Set<PosixFilePermission>> ownerOnly =
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    try (SeekableByteChannel channel =
            Files.newByteChannel(keyFile, CREATE_TRUNCATE_WRITE, ownerOnly);
        OutputStream out = Channels.newOutputStream(channel)) {
      out.write(content);
    } catch (UnsupportedOperationException e) {
      Files.write(keyFile, content);
    }
  }

  /** POSIX 0600 归一化；非 POSIX 文件系统（如 Windows）降级跳过，权限问题只告警、不阻断启动。 */
  private static void restrictToOwner(Path keyFile) {
    try {
      Files.setPosixFilePermissions(
          keyFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException e) {
      LOG.warn("当前文件系统不支持 POSIX 权限，master.key 未收紧到 0600（请自行保护该文件）");
    } catch (IOException e) {
      LOG.warn("master.key 权限收紧失败（{}），请自行确认该文件不可被他人读取", e.getMessage());
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
}
