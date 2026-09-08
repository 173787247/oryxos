package io.oryxos.core.secret;

/**
 * 凭证解密失败（022）：主密钥不符或密文损坏（GCM 认证失败/格式坏）。
 *
 * <p>message 只描述问题，不得携带密钥值与明文片段（FR-009）。
 */
public class SecretDecryptException extends RuntimeException {

  public SecretDecryptException(String message) {
    super(message);
  }

  public SecretDecryptException(String message, Throwable cause) {
    super(message, cause);
  }
}
