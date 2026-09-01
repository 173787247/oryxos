package io.oryxos.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.secret.SecretCipher;
import io.oryxos.core.secret.SecretDecryptException;
import io.oryxos.core.secret.SensitiveConfigKeys;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 落库凭证的启动迁移与密钥守卫（022，AuditSchemaUpgrade 同位）：
 *
 * <ul>
 *   <li>迁移：扫描 providers.api_key 与 notify_channels.config 敏感项——明文（非空且无 enc:v1: 前缀）加密回写并计数， 日志「已加密 N
 *       条凭证」；前缀判别使重复启动/中断续跑天然幂等（FR-005）
 *   <li>守卫：密文值试解密——存在密文且<b>全部</b>失败 = 主密钥不匹配 → 拒启并指路恢复（SC-004）； <b>部分</b>失败 = 单行损坏 → WARN
 *       定位条目继续启动（FR-010）；全新库直接通过（SC-003）
 * </ul>
 *
 * <p>直接操作 entity/repository（不经注册表 Def 往返）：迁移需要看到库中原始值做前缀判别， 而注册表 toDef 已解密——两层职责分开。
 */
public final class SecretMigration {

  private static final Logger LOG = LoggerFactory.getLogger(SecretMigration.class);

  private final LlmProviderRepository providerRepository;
  private final NotifyChannelRepository channelRepository;
  private final SecretCipher cipher;
  private final ObjectMapper mapper = new ObjectMapper();

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository/cipher 均为装配层注入的共享单例，存同一引用正是意图。")
  public SecretMigration(
      LlmProviderRepository providerRepository,
      NotifyChannelRepository channelRepository,
      SecretCipher cipher) {
    this.providerRepository = providerRepository;
    this.channelRepository = channelRepository;
    this.cipher = cipher;
  }

  /**
   * 启动执行：先守卫（密钥不匹配尽早拦截），后迁移明文。
   *
   * <p>notify_channels 的 config 列在极老库上由 NotifyChannelSchemaMigration（CommandLineRunner，
   * 晚于本迁移）补齐——列未就绪时渠道表扫描 WARN 跳过、下次启动自愈；此时库中也不可能存在渠道密文， providers 守卫不受影响，拒启语义完整。
   */
  public void run() {
    List<String> undecryptable = new ArrayList<>();
    int ciphertextCount = guard(undecryptable);
    if (ciphertextCount > 0 && undecryptable.size() == ciphertextCount) {
      // 全部密文都解不开：不是数据坏，是钥匙不对——拒启，绝不静默降级或清数据（FR-006）
      throw new IllegalStateException(
          "已有 "
              + ciphertextCount
              + " 条加密凭证无法解密。可能：主密钥丢失或被更换。恢复：找回原密钥（ORYXOS_MASTER_KEY 或 .oryxos/master.key）；"
              + "或经管理台删除并重新录入凭证");
    }
    undecryptable.forEach(entry -> LOG.warn("凭证密文损坏（{}），该条按缺失处理，其余凭证不受影响", sanitize(entry)));

    int migrated = migratePlaintext();
    if (migrated > 0) {
      LOG.info("已加密 {} 条凭证（022 存量明文迁移）", migrated);
    }
  }

  /** 试解密全部密文值；返回密文总数，解不开的记入清单（"provider <n>" / "channel <n>.<key>"）。 */
  private int guard(List<String> undecryptable) {
    int ciphertextCount = 0;
    for (LlmProvider provider : providerRepository.findAll()) {
      if (cipher.isEncrypted(provider.getApiKey())) {
        ciphertextCount++;
        if (!canDecrypt(provider.getApiKey())) {
          undecryptable.add("provider " + provider.getName());
        }
      }
    }
    for (NotifyChannel channel : channels()) {
      for (Map.Entry<String, String> entry : readConfig(channel.getConfig()).entrySet()) {
        if (cipher.isEncrypted(entry.getValue())) {
          ciphertextCount++;
          if (!canDecrypt(entry.getValue())) {
            undecryptable.add("channel " + channel.getName() + "." + entry.getKey());
          }
        }
      }
    }
    return ciphertextCount;
  }

  /** 极老库 config 列未就绪时返回空表（WARN 跳过，下次启动自愈）；见 {@link #run()} 注释。 */
  private List<NotifyChannel> channels() {
    try {
      return channelRepository.findAll();
    } catch (org.springframework.dao.DataAccessException e) {
      LOG.warn(
          "notify_channels 尚未就绪（{}），本次启动跳过该表的凭证迁移，下次启动自愈",
          sanitize(e.getMostSpecificCause().getMessage()));
      return List.of();
    }
  }

  /** 明文敏感值 → 加密回写；前缀判别保证幂等。 */
  private int migratePlaintext() {
    int migrated = 0;
    for (LlmProvider provider : providerRepository.findAll()) {
      String apiKey = provider.getApiKey();
      if (apiKey != null && !apiKey.isEmpty() && !cipher.isEncrypted(apiKey)) {
        provider.setApiKey(cipher.encrypt(apiKey));
        providerRepository.save(provider);
        migrated++;
      }
    }
    for (NotifyChannel channel : channels()) {
      Map<String, String> config = readConfig(channel.getConfig());
      boolean changed = false;
      Map<String, String> out = new LinkedHashMap<>();
      for (Map.Entry<String, String> entry : config.entrySet()) {
        String value = entry.getValue();
        boolean plaintextSecret =
            SensitiveConfigKeys.isSensitive(entry.getKey())
                && value != null
                && !value.isEmpty()
                && !cipher.isEncrypted(value);
        if (plaintextSecret) {
          out.put(entry.getKey(), cipher.encrypt(value));
          changed = true;
          migrated++;
        } else {
          out.put(entry.getKey(), value);
        }
      }
      if (changed) {
        channel.setConfig(writeConfig(out));
        channelRepository.save(channel);
      }
    }
    return migrated;
  }

  private boolean canDecrypt(String stored) {
    try {
      // 密文输入下 decrypt 恒返回非 null（null 仅当输入为 null）——用返回值参与判定
      return cipher.decrypt(stored) != null;
    } catch (SecretDecryptException e) {
      return false;
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }

  private Map<String, String> readConfig(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      return Map.of();
    }
    try {
      return mapper.readValue(configJson, new TypeReference<Map<String, String>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      LOG.warn("通知渠道 config 反序列化失败，跳过迁移该行: {}", sanitize(e.getOriginalMessage()));
      return Map.of();
    }
  }

  private String writeConfig(Map<String, String> config) {
    try {
      return mapper.writeValueAsString(config);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("通知渠道 config 序列化失败: " + e.getOriginalMessage(), e);
    }
  }
}
