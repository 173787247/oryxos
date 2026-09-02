package io.oryxos.core.secret;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@link SecretCipher} 的本地主密钥实现（022，R1）：AES-256-GCM，JDK 内置零新依赖。
 *
 * <p>每次加密生成随机 12 字节 IV（同一明文两次加密密文不同——FindSecBugs STATIC_IV 以正确实现自然通过，不 Suppress）； GCM
 * 认证标签使密文被篡改/截断时解密即失败（边界场景「密文行被改坏」的检测机制）。 异常 message 不携带明文与密钥（FR-009）。
 */
public final class LocalMasterKeyCipher implements SecretCipher {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKeySpec key;

  public LocalMasterKeyCipher(byte[] masterKey) {
    this.key = new SecretKeySpec(masterKey, "AES");
  }

  @Override
  public String encrypt(String plaintext) {
    if (plaintext == null || plaintext.isEmpty()) {
      return plaintext;
    }
    try {
      byte[] iv = new byte[IV_BYTES];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, out, 0, iv.length);
      System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
      return PREFIX + Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      // 加密侧失败=环境级问题（JCE 不可用等），非数据问题
      throw new IllegalStateException("凭证加密失败（AES-GCM 初始化异常）", e);
    }
  }

  @Override
  public String decrypt(String stored) {
    if (stored == null || stored.isEmpty() || !isEncrypted(stored)) {
      return stored; // 明文（迁移期兼容）与空值原样返回
    }
    byte[] payload;
    try {
      payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
    } catch (IllegalArgumentException e) {
      throw new SecretDecryptException("密文编码损坏（非法 Base64）");
    }
    if (payload.length <= IV_BYTES) {
      throw new SecretDecryptException("密文被截断（长度不足）");
    }
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
      byte[] plaintext = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      // GCM 认证失败：主密钥不符或密文被篡改——message 不含明文/密钥（FR-009）
      throw new SecretDecryptException("凭证解密失败：主密钥不符或密文损坏", e);
    }
  }
}
