package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxos.core.notify.NotifyChannelDef;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.secret.LocalMasterKeyCipher;
import io.oryxos.core.secret.SecretCipher;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 022 US1/US2：注册表收口加解密透明性、迁移幂等、密钥守卫拒启、坏行隔离。 */
@org.springframework.transaction.annotation.Transactional
abstract class SecretStorageContractTest {

  private static final byte[] KEY = keyOf(1);
  private static final byte[] WRONG_KEY = keyOf(2);

  @Autowired LlmProviderRepository providerRepository;
  @Autowired NotifyChannelRepository channelRepository;

  private final SecretCipher cipher = new LocalMasterKeyCipher(KEY);

  private static byte[] keyOf(int seed) {
    byte[] key = new byte[32];
    key[0] = (byte) seed;
    return key;
  }

  @Test
  void save后库中为密文_find读出明文() {
    JpaProviderRegistry registry = new JpaProviderRegistry(providerRepository, cipher);
    registry.save(new ProviderDef("p-enc", "sk-real-key-123", "https://api.example.com", "d"));

    String stored = providerRepository.findById("p-enc").orElseThrow().getApiKey();
    assertThat(stored).startsWith(SecretCipher.PREFIX).doesNotContain("sk-real-key-123");
    assertThat(registry.find("p-enc").orElseThrow().apiKey()).isEqualTo("sk-real-key-123");
  }

  @Test
  void notify配置_仅敏感项加密_普通项原样() {
    JpaNotifyChannelRegistry registry = new JpaNotifyChannelRegistry(channelRepository, cipher);
    registry.save(
        new NotifyChannelDef(
            "mail-enc",
            "email",
            "smtp://placeholder",
            "d",
            Map.of("host", "smtp.example.com", "password", "p@ss123", "from", "a@b.c")));

    String storedJson = channelRepository.findById("mail-enc").orElseThrow().getConfig();
    assertThat(storedJson)
        .contains("smtp.example.com") // 普通项明文
        .contains("a@b.c")
        .doesNotContain("p@ss123"); // 敏感项密文
    assertThat(storedJson).contains(SecretCipher.PREFIX);

    Map<String, String> read = registry.find("mail-enc").orElseThrow().config();
    assertThat(read).containsEntry("password", "p@ss123").containsEntry("host", "smtp.example.com");
  }

  @Test
  void notify渠道_webhook_url落库即密文_find读出明文() {
    JpaNotifyChannelRegistry registry = new JpaNotifyChannelRegistry(channelRepository, cipher);
    String url = "https://open.feishu.cn/open-apis/bot/v2/hook/secret-hook-id";
    registry.save(new NotifyChannelDef("lark-enc", "feishu", url, "d", Map.of("token", "t-1")));

    String storedUrl = channelRepository.findById("lark-enc").orElseThrow().getUrl();
    assertThat(storedUrl).startsWith(SecretCipher.PREFIX).doesNotContain("secret-hook-id");
    assertThat(registry.find("lark-enc").orElseThrow().url()).isEqualTo(url);
  }

  @Test
  void notify渠道_email的url不加密() {
    JpaNotifyChannelRegistry registry = new JpaNotifyChannelRegistry(channelRepository, cipher);
    registry.save(
        new NotifyChannelDef(
            "mail-plain-url",
            "email",
            "smtp://placeholder",
            "d",
            Map.of("host", "smtp.example.com", "password", "p@ss")));

    assertThat(channelRepository.findById("mail-plain-url").orElseThrow().getUrl())
        .isEqualTo("smtp://placeholder");
    assertThat(registry.find("mail-plain-url").orElseThrow().url()).isEqualTo("smtp://placeholder");
  }

  @Test
  void 存量明文webhook_url_迁移后变密文且幂等_find读出明文() {
    NotifyChannel legacy = new NotifyChannel();
    legacy.setName("hook-legacy");
    legacy.setType("feishu");
    legacy.setUrl("https://open.feishu.cn/open-apis/bot/v2/hook/plain-hook-id");
    legacy.setConfig("{\"token\":\"t-legacy\"}");
    channelRepository.save(legacy);

    SecretMigration migration = new SecretMigration(providerRepository, channelRepository, cipher);
    migration.run();

    NotifyChannel migrated = channelRepository.findById("hook-legacy").orElseThrow();
    assertThat(migrated.getUrl()).startsWith(SecretCipher.PREFIX).doesNotContain("plain-hook-id");
    assertThat(cipher.decrypt(migrated.getUrl()))
        .isEqualTo("https://open.feishu.cn/open-apis/bot/v2/hook/plain-hook-id");
    assertThat(migrated.getConfig()).doesNotContain("t-legacy"); // 既有 config 迁移照常
    assertThat(
            new JpaNotifyChannelRegistry(channelRepository, cipher)
                .find("hook-legacy")
                .orElseThrow()
                .url())
        .isEqualTo("https://open.feishu.cn/open-apis/bot/v2/hook/plain-hook-id"); // 读出给适配器的是明文

    String migratedUrl = migrated.getUrl();
    String migratedConfig = migrated.getConfig();
    migration.run(); // 幂等：二次运行不改变已密文值
    assertThat(channelRepository.findById("hook-legacy").orElseThrow().getUrl())
        .isEqualTo(migratedUrl);
    assertThat(channelRepository.findById("hook-legacy").orElseThrow().getConfig())
        .isEqualTo(migratedConfig);
  }

  @Test
  void 存量email_url_迁移后仍为明文() {
    NotifyChannel legacy = new NotifyChannel();
    legacy.setName("mail-legacy-url");
    legacy.setType("email");
    legacy.setUrl("smtp://placeholder");
    channelRepository.save(legacy);

    new SecretMigration(providerRepository, channelRepository, cipher).run();

    assertThat(channelRepository.findById("mail-legacy-url").orElseThrow().getUrl())
        .isEqualTo("smtp://placeholder");
  }

  /**
   * 独立缺陷（022 范围补齐期间发现）：迁移在 config JSON 解析失败时若按空 Map 回写，会把坏 JSON 覆盖成 {@code {}}—— 数据丢失。这里钉住「只加密
   * url、config 原文一个字节不变」。
   */
  @Test
  void 坏config_迁移只加密url_config原文字节不变() {
    String corruptConfig = "{\"host\":\"h\",\"password\":\"legacy-pass\"";
    NotifyChannel channel = new NotifyChannel();
    channel.setName("hook-corrupt-config");
    channel.setType("webhook");
    channel.setUrl("https://hooks.example.com/T000/B000/plain-webhook");
    channel.setConfig(corruptConfig);
    channelRepository.save(channel);

    new SecretMigration(providerRepository, channelRepository, cipher).run();

    NotifyChannel migrated = channelRepository.findById("hook-corrupt-config").orElseThrow();
    assertThat(migrated.getUrl()).startsWith(SecretCipher.PREFIX);
    assertThat(migrated.getConfig()).isEqualTo(corruptConfig);
  }

  @Test
  void url密文损坏_读出按缺失处理_不拖垮列表() {
    JpaNotifyChannelRegistry registry = new JpaNotifyChannelRegistry(channelRepository, cipher);
    registry.save(new NotifyChannelDef("hook-good", "feishu", "https://x/good", "d", Map.of()));
    NotifyChannel corrupt = new NotifyChannel();
    corrupt.setName("hook-corrupt");
    corrupt.setType("feishu");
    corrupt.setUrl(SecretCipher.PREFIX + "AAAA-corrupted-not-real-ciphertext");
    channelRepository.save(corrupt);

    var defs = registry.list();
    assertThat(defs).extracting(NotifyChannelDef::name).contains("hook-good", "hook-corrupt");
    assertThat(
            defs.stream().filter(d -> d.name().equals("hook-good")).findFirst().orElseThrow().url())
        .isEqualTo("https://x/good");
    assertThat(
            defs.stream()
                .filter(d -> d.name().equals("hook-corrupt"))
                .findFirst()
                .orElseThrow()
                .url())
        .isNull();
  }

  @Test
  void 只有url密文且密钥不符_仍拒启() {
    NotifyChannel channel = new NotifyChannel();
    channel.setName("hook-guard");
    channel.setType("feishu");
    channel.setUrl(SecretCipher.PREFIX + "AAAA-not-real-ciphertext");
    channelRepository.save(channel);

    SecretMigration wrongKeyMigration =
        new SecretMigration(
            providerRepository, channelRepository, new LocalMasterKeyCipher(WRONG_KEY));

    assertThatThrownBy(wrongKeyMigration::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("无法解密");
  }

  @Test
  void 存量明文_迁移后变密文且幂等() {
    LlmProvider legacy = new LlmProvider();
    legacy.setName("p-legacy");
    legacy.setApiKey("sk-plaintext-legacy");
    providerRepository.save(legacy);
    NotifyChannel channel = new NotifyChannel();
    channel.setName("mail-legacy");
    channel.setType("email");
    channel.setUrl("smtp://placeholder");
    channel.setConfig("{\"host\":\"h\",\"password\":\"legacy-pass\"}"); // 时间戳由 @PrePersist 生成
    channelRepository.save(channel);

    SecretMigration migration = new SecretMigration(providerRepository, channelRepository, cipher);
    migration.run();

    String migratedKey = providerRepository.findById("p-legacy").orElseThrow().getApiKey();
    String migratedConfig = channelRepository.findById("mail-legacy").orElseThrow().getConfig();
    assertThat(migratedKey).startsWith(SecretCipher.PREFIX);
    assertThat(migratedConfig).doesNotContain("legacy-pass").contains("\"host\":\"h\"");

    migration.run(); // 幂等：二次运行不改变已密文值
    assertThat(providerRepository.findById("p-legacy").orElseThrow().getApiKey())
        .isEqualTo(migratedKey);
    assertThat(channelRepository.findById("mail-legacy").orElseThrow().getConfig())
        .isEqualTo(migratedConfig);
  }

  @Test
  void 密钥不匹配_全部密文解不开_拒启且文案含恢复路径() {
    new JpaProviderRegistry(providerRepository, cipher)
        .save(new ProviderDef("p-guard", "sk-guard-key", null, "d"));

    SecretMigration wrongKeyMigration =
        new SecretMigration(
            providerRepository, channelRepository, new LocalMasterKeyCipher(WRONG_KEY));

    assertThatThrownBy(wrongKeyMigration::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("无法解密")
        .hasMessageContaining("找回原密钥")
        .hasMessageContaining("重新录入"); // 两条恢复路径（SC-004）
  }

  @Test
  void 部分密文损坏_WARN继续启动_坏行隔离() {
    JpaProviderRegistry registry = new JpaProviderRegistry(providerRepository, cipher);
    registry.save(new ProviderDef("p-good", "sk-good-key", null, "d"));
    LlmProvider corrupt = new LlmProvider();
    corrupt.setName("p-corrupt");
    corrupt.setApiKey(SecretCipher.PREFIX + "AAAA-corrupted-not-real-ciphertext");
    providerRepository.save(corrupt);

    // 部分坏 ≠ 密钥不匹配：迁移守卫 WARN 不抛（FR-010）
    new SecretMigration(providerRepository, channelRepository, cipher).run();

    // list 照常返回：好行明文、坏行凭证按缺失处理
    var defs = registry.list();
    assertThat(defs).extracting(ProviderDef::name).contains("p-good", "p-corrupt");
    assertThat(
            defs.stream().filter(d -> d.name().equals("p-good")).findFirst().orElseThrow().apiKey())
        .isEqualTo("sk-good-key");
    assertThat(
            defs.stream()
                .filter(d -> d.name().equals("p-corrupt"))
                .findFirst()
                .orElseThrow()
                .apiKey())
        .isNull();
  }

  @Test
  void 全新库零凭证_守卫直接通过() {
    providerRepository.deleteAll();
    channelRepository.deleteAll();
    new SecretMigration(providerRepository, channelRepository, cipher).run(); // 不抛即通过（SC-003）
  }
}
