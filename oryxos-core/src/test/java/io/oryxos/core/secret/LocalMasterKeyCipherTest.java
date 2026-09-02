package io.oryxos.core.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class LocalMasterKeyCipherTest {

  private static final byte[] KEY = new byte[32]; // 全零测试密钥，仅单测用

  private final LocalMasterKeyCipher cipher = new LocalMasterKeyCipher(KEY);

  @Test
  void 往返一致() {
    String plaintext = "sk-real-api-key-中文也行-12345";
    String stored = cipher.encrypt(plaintext);
    assertThat(stored).startsWith(SecretCipher.PREFIX).doesNotContain(plaintext);
    assertThat(cipher.decrypt(stored)).isEqualTo(plaintext);
  }

  @Test
  void 同明文两次加密密文不同_随机IV() {
    String a = cipher.encrypt("same-secret");
    String b = cipher.encrypt("same-secret");
    assertThat(a).isNotEqualTo(b); // IV 每次随机——不可做相等性判定是有意为之
    assertThat(cipher.decrypt(a)).isEqualTo(cipher.decrypt(b));
  }

  @Test
  void 前缀判别() {
    assertThat(cipher.isEncrypted(cipher.encrypt("v"))).isTrue();
    assertThat(cipher.isEncrypted("sk-plaintext")).isFalse();
    assertThat(cipher.isEncrypted(null)).isFalse();
  }

  @Test
  void 密文篡改或截断_抛SecretDecryptException() {
    String stored = cipher.encrypt("secret-value");
    // 篡改末字符（GCM 认证必失败）
    String tampered = stored.substring(0, stored.length() - 2) + "AA";
    assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(SecretDecryptException.class);
    // 截断到只剩前缀+短载荷
    String truncated =
        SecretCipher.PREFIX + Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
    assertThatThrownBy(() -> cipher.decrypt(truncated)).isInstanceOf(SecretDecryptException.class);
    // 非法 Base64
    assertThatThrownBy(() -> cipher.decrypt(SecretCipher.PREFIX + "!!not-base64!!"))
        .isInstanceOf(SecretDecryptException.class);
  }

  @Test
  void null与空串原样返回() {
    assertThat(cipher.encrypt(null)).isNull();
    assertThat(cipher.encrypt("")).isEmpty();
    assertThat(cipher.decrypt(null)).isNull();
    assertThat(cipher.decrypt("")).isEmpty();
  }

  @Test
  void 无前缀明文decrypt原样返回_迁移期兼容() {
    assertThat(cipher.decrypt("sk-legacy-plaintext")).isEqualTo("sk-legacy-plaintext");
  }

  @Test
  void 解密失败异常message不含密钥与明文() {
    // 用另一把钥匙加密 → 本 cipher 解密必失败（密钥不符场景）
    byte[] otherKey = new byte[32];
    otherKey[0] = 42;
    String foreign = new LocalMasterKeyCipher(otherKey).encrypt("top-secret-plain");
    assertThatThrownBy(() -> cipher.decrypt(foreign))
        .isInstanceOf(SecretDecryptException.class)
        .satisfies(
            e -> {
              String message = e.getMessage();
              assertThat(message).doesNotContain("top-secret-plain");
              assertThat(message).doesNotContain(Base64.getEncoder().encodeToString(KEY));
              assertThat(message).doesNotContain(Base64.getEncoder().encodeToString(otherKey));
            });
  }
}
