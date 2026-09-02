package io.oryxos.storage;

import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.core.secret.SecretCipher;
import io.oryxos.core.secret.SecretDecryptException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ProviderRegistry} 的 SQLite/JPA 实现：providers 表 ↔ {@link ProviderDef} 互转。
 *
 * <p>022：api_key 在此收口加解密——save 落库前 encrypt、toDef 读出时 decrypt，上游（ProviderService/ controller/YAML
 * 播种）拿到的永远是明文 Def，Registry 契约零改动（R2 红线）。单条解密失败只 WARN 并以 null key 返回，不拖垮 list（FR-010）。
 */
public class JpaProviderRegistry implements ProviderRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(JpaProviderRegistry.class);

  /** 明文直通：旧构造/既有测试兼容位——不加密不解密，行为与 022 之前等价。 */
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

  private final LlmProviderRepository repository;
  private final SecretCipher cipher;

  /** 旧构造兼容（既有测试直构点）：明文直通，行为与 022 之前等价。 */
  public JpaProviderRegistry(LlmProviderRepository repository) {
    this(repository, PLAINTEXT_PASSTHROUGH);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "repository/cipher 均为装配层注入的共享单例，存同一引用正是意图。")
  public JpaProviderRegistry(LlmProviderRepository repository, SecretCipher cipher) {
    this.repository = repository;
    this.cipher = cipher;
  }

  @Override
  public List<ProviderDef> list() {
    return repository.findAll().stream().map(this::toDef).toList();
  }

  @Override
  public Optional<ProviderDef> find(String name) {
    return repository.findById(name).map(this::toDef);
  }

  @Override
  public boolean exists(String name) {
    return repository.existsById(name);
  }

  @Override
  public ProviderDef save(ProviderDef provider) {
    LlmProvider entity = repository.findById(provider.name()).orElseGet(LlmProvider::new);
    entity.setName(provider.name());
    entity.setApiKey(cipher.encrypt(provider.apiKey())); // 022：落库即密文
    entity.setBaseUrl(provider.baseUrl());
    entity.setDescription(provider.description());
    return toDef(repository.save(entity));
  }

  @Override
  public void delete(String name) {
    repository.deleteById(name);
  }

  private ProviderDef toDef(LlmProvider e) {
    return new ProviderDef(e.getName(), decryptLenient(e), e.getBaseUrl(), e.getDescription());
  }

  /** 单条坏密文不拖垮整体（FR-010）：WARN 定位 provider 名，凭证以 null 返回（使用时清晰报"key 缺失"）。 */
  private String decryptLenient(LlmProvider e) {
    try {
      return cipher.decrypt(e.getApiKey());
    } catch (SecretDecryptException ex) {
      LOG.warn(
          "Provider {} 的 api_key 密文无法解密（{}），本条凭证按缺失处理",
          sanitize(e.getName()),
          sanitize(ex.getMessage()));
      return null;
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
