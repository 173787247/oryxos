package io.oryxos.core.secret;

/**
 * 落库凭证加解密契约（022）：调用方（存储层注册表）只认本接口，实现可替换—— 本版本为 {@link LocalMasterKeyCipher}（本地主密钥），未来对接 KMS/Vault
 * 换实现不动调用方（VectorStore 两步走同一手法）。
 *
 * <p>密文形态 {@code enc:v1:<Base64(IV‖ct‖tag)>}：前缀承担明文/密文判别（迁移幂等依据）与版本升级识别位。
 */
public interface SecretCipher {

  /** 密文版本前缀，对外形态固化（契约 §1.3）。 */
  String PREFIX = "enc:v1:";

  /** 明文 → 密文；null/空串原样返回（不产生前缀，保持现状语义）。 */
  String encrypt(String plaintext);

  /** 存储值 → 明文；无前缀视为明文原样返回（迁移期兼容）；null/空原样。 解密失败（密钥不符/密文损坏）抛 {@link SecretDecryptException}。 */
  String decrypt(String stored);

  /** 前缀判别：迁移扫描与密钥守卫复用。 */
  default boolean isEncrypted(String value) {
    return value != null && value.startsWith(PREFIX);
  }
}
