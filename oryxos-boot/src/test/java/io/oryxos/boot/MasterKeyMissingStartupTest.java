package io.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 主密钥丢失时的启动路径（022）：拒启提示不能把运维指向本次启动<b>自己生成</b>的密钥文件。
 *
 * <p>缺陷形态：装配顺序是「先解析主密钥（缺文件就立即生成并落盘）→ 再跑密钥守卫」。于是钥匙丢失 + 库里有密文时，盘上先多出一把
 * 新钥匙，守卫拿它解不开任何东西并拒启，提示「找回原密钥（ORYXOS_MASTER_KEY 或 .oryxos/master.key）」——那个文件此刻
 * <b>存在且是错的</b>，照着提示去"找回"只会拿到本次启动刚写的那把。
 *
 * <p>修复后：自动生成的那把先留在内存里，落盘排在守卫<b>通过之后</b>。本用例起真上下文跑完整启动路径（守卫在 {@code secretMigration} bean
 * 里），因此钉住的正是装配顺序本身，而不是某个被单独调用的方法。
 */
class MasterKeyMissingStartupTest {

  private static final String DB_FILE = "master-key.db";
  private static final String PROVIDER = "deepseek";
  private static final String API_KEY = "sk-seed-plaintext";

  @TempDir Path root;

  @Test
  void 密钥丢失_拒启_且本次不留下新生成的密钥文件() throws Exception {
    Workspace workspace = seededWorkspace();
    Files.delete(workspace.keyFile());

    Throwable failure =
        catchThrowable(
            () -> {
              try (ConfigurableApplicationContext ignored = boot(workspace)) {
                // 不该启动成功
              }
            });

    assertThat(failure).isNotNull();
    // 真推迟：守卫在落盘之前，所以整次失败启动里磁盘上从没出现过这把新钥匙
    assertThat(workspace.keyFile()).doesNotExist();
    String messages = messageChain(failure);
    assertThat(messages)
        .contains("无法解密") // 守卫判定的原句
        .contains("没有写入磁盘") // 本次生成的钥匙没有落盘
        .contains("不要去那里找原密钥") // 别照旧提示去找那个文件
        .contains("不要为了重新生成而删除备份"); // 最坏情况的兜底
  }

  @Test
  void 原密钥在_正常启动并能读回明文凭证() throws Exception {
    Workspace workspace = seededWorkspace();

    try (ConfigurableApplicationContext context = boot(workspace)) {
      ProviderDef provider = context.getBean(ProviderRegistry.class).find(PROVIDER).orElseThrow();
      assertThat(provider.apiKey()).isEqualTo(API_KEY); // 同一把钥匙，密文照常解开
    }
    assertThat(workspace.keyFile()).exists(); // 已存在的钥匙不会被改写
  }

  /** 用一把已知钥匙落一条加密凭证，模拟"曾经正常跑过、后来钥匙丢了"的库。 */
  private Workspace seededWorkspace() throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Files.createDirectories(workspace.resolve("agents"));
    Files.createDirectories(workspace.resolve("memory"));
    Path keyFile = workspace.resolve("master.key");
    byte[] key = new byte[32];
    key[0] = 7;
    key[31] = 9;
    Files.writeString(keyFile, Base64.getEncoder().encodeToString(key), StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));

    Workspace ws = new Workspace(workspace, keyFile, "jdbc:sqlite:" + root.resolve(DB_FILE));
    try (ConfigurableApplicationContext context = boot(ws)) {
      // YAML 播种会经注册表落库（api_key 以这把钥匙加密），启动一次即可
      assertThat(context.getBean(ProviderRegistry.class).find(PROVIDER).orElseThrow().apiKey())
          .isEqualTo(API_KEY);
    }
    return ws;
  }

  private static ConfigurableApplicationContext boot(Workspace workspace) {
    return new SpringApplicationBuilder(OryxOsRuntime.class)
        .web(WebApplicationType.NONE)
        .run(
            "--spring.main.banner-mode=off",
            "--oryxos.root=" + workspace.root(),
            "--spring.datasource.url=" + workspace.dbUrl(),
            "--oryxos.providers[0].name=" + PROVIDER,
            "--oryxos.providers[0].api-key=" + API_KEY,
            "--oryxos.providers[0].base-url=https://seed.example/v1");
  }

  /** 沿 cause 链收集 message：Spring 会把 bean 创建失败包一层，真正的拒启说明在 cause 里。 */
  private static String messageChain(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current.getMessage() != null) {
        messages.append(current.getMessage()).append('\n');
      }
    }
    return messages.toString();
  }

  /** 一次"工作过"的部署：工作区根、密钥文件、SQLite 库。 */
  private record Workspace(Path root, Path keyFile, String dbUrl) {}
}
