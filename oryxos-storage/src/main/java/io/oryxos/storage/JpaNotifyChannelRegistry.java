package io.oryxos.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.notify.NotifyChannelDef;
import io.oryxos.core.notify.NotifyChannelRegistry;
import io.oryxos.core.secret.SecretCipher;
import io.oryxos.core.secret.SecretDecryptException;
import io.oryxos.core.secret.SensitiveConfigKeys;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link NotifyChannelRegistry} 的 SQLite/JPA 实现：notify_channels 表 ↔ {@link NotifyChannelDef} 互转。
 * {@code config} 列是 JSON 文本，序列化沿用 {@link JpaSessionManager} 的手工 ObjectMapper 模式。
 *
 * <p>022：config 内命中 {@link SensitiveConfigKeys} 名录的项在此收口逐值加解密（名录外 host/port/from
 * 等保持明文可读）；上游拿到的永远是明文 Def，Registry 契约零改动。单项坏密文 WARN 定位渠道+字段、 该项以 null 返回，不拖垮整体（FR-010）。
 */
public class JpaNotifyChannelRegistry implements NotifyChannelRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(JpaNotifyChannelRegistry.class);

  /** 明文直通：旧构造/既有测试兼容位——行为与 022 之前等价。 */
  private static final SecretCipher PLAINTEXT_PASSTHROUGH =
      new SecretCipher() {
        @Override
        public String encrypt(String plaintext) {
          return plaintext;
        }

        @Override
        public String decrypt(String stored) {
          return stored;
        }
      };

  private final NotifyChannelRepository repository;
  private final SecretCipher cipher;

  private final ObjectMapper mapper = new ObjectMapper();

  /** 旧构造兼容（既有测试直构点）：明文直通，行为与 022 之前等价。 */
  public JpaNotifyChannelRegistry(NotifyChannelRepository repository) {
    this(repository, PLAINTEXT_PASSTHROUGH);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository/cipher 均为装配层注入的共享单例，存同一引用正是意图。")
  public JpaNotifyChannelRegistry(NotifyChannelRepository repository, SecretCipher cipher) {
    this.repository = repository;
    this.cipher = cipher;
  }

  @Override
  public List<NotifyChannelDef> list() {
    return repository.findAll().stream().map(this::toDef).toList();
  }

  @Override
  public Optional<NotifyChannelDef> find(String name) {
    return repository.findById(name).map(this::toDef);
  }

  @Override
  public boolean exists(String name) {
    return repository.existsById(name);
  }

  @Override
  public NotifyChannelDef save(NotifyChannelDef channel) {
    NotifyChannel entity = repository.findById(channel.name()).orElseGet(NotifyChannel::new);
    entity.setName(channel.name());
    entity.setType(channel.type());
    entity.setUrl(channel.url());
    entity.setDescription(channel.description());
    entity.setConfig(writeConfig(encryptSensitive(channel.config()))); // 022：敏感项落库即密文
    return toDef(repository.save(entity));
  }

  @Override
  public void delete(String name) {
    repository.deleteById(name);
  }

  private NotifyChannelDef toDef(NotifyChannel e) {
    return new NotifyChannelDef(
        e.getName(),
        e.getType(),
        e.getUrl(),
        e.getDescription(),
        decryptSensitive(e.getName(), readConfig(e.getConfig())));
  }

  /** config 敏感项逐值加密；名录外原样（host/port/from 保持可读，R5）。 */
  private Map<String, String> encryptSensitive(Map<String, String> config) {
    if (config == null || config.isEmpty()) {
      return config;
    }
    Map<String, String> out = new LinkedHashMap<>();
    config.forEach(
        (key, value) ->
            out.put(key, SensitiveConfigKeys.isSensitive(key) ? cipher.encrypt(value) : value));
    return out;
  }

  /**
   * 敏感项逐值解密；单项坏密文 WARN 定位渠道+字段、该项整体省略（Def 的 Map.copyOf 不接受 null 值）， 不拖垮整体（FR-010）——下游使用时按"未配置"清晰报错。
   */
  private Map<String, String> decryptSensitive(String channelName, Map<String, String> config) {
    if (config == null || config.isEmpty()) {
      return config;
    }
    Map<String, String> out = new LinkedHashMap<>();
    config.forEach(
        (key, value) -> {
          if (!SensitiveConfigKeys.isSensitive(key)) {
            out.put(key, value);
            return;
          }
          try {
            out.put(key, cipher.decrypt(value));
          } catch (SecretDecryptException ex) {
            LOG.warn(
                "通知渠道 {} 的 {} 密文无法解密（{}），本项按缺失处理",
                sanitize(channelName),
                sanitize(key),
                sanitize(ex.getMessage()));
          }
        });
    return out;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }

  private String writeConfig(Map<String, String> config) {
    if (config == null || config.isEmpty()) {
      return null;
    }
    try {
      return mapper.writeValueAsString(config);
    } catch (JsonProcessingException ex) {
      // config 写不进去等于渠道配置丢失，显式失败而非静默存空
      throw new IllegalStateException("通知渠道 config 序列化失败: " + ex.getOriginalMessage(), ex);
    }
  }

  private Map<String, String> readConfig(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      return Map.of();
    }
    try {
      return mapper.readValue(configJson, new TypeReference<Map<String, String>>() {});
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("通知渠道 config 反序列化失败: " + ex.getOriginalMessage(), ex);
    }
  }
}
