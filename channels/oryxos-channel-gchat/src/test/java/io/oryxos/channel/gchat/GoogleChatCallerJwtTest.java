package io.oryxos.channel.gchat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 钉住 Google Chat 官方 JWT 校验的四项判据：签名、issuer、audience、exp。
 *
 * <p>用本地生成的 RSA 密钥签真 JWT（JWKS 用 {@code ImmutableJWKSet} 注入），因此不打网络、也不依赖 Google 的密钥轮换。校验器接受注入的
 * {@code JWKSource} 正是为了这个。
 */
class GoogleChatCallerJwtTest {

  private static final String ISSUER = "chat@system.gserviceaccount.com";
  private static final String PROJECT_NUMBER = "123456789";

  private static RSAKey signingKey;
  private static RSAKey foreignKey;
  private static JWKSource<SecurityContext> keySource;

  @BeforeAll
  static void generateKeys() throws Exception {
    signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
    foreignKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
    keySource = new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
  }

  private static GoogleChatCallerJwt verifier() {
    return new GoogleChatCallerJwt(PROJECT_NUMBER, keySource);
  }

  private static String token(RSAKey signer, String issuer, String audience, Instant expiration)
      throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .expirationTime(Date.from(expiration))
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signer.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(signer));
    return jwt.serialize();
  }

  private static String validToken() throws Exception {
    return token(signingKey, ISSUER, PROJECT_NUMBER, Instant.now().plusSeconds(600));
  }

  @Test
  @DisplayName("合法 JWT 通过")
  void acceptsValidToken() throws Exception {
    assertTrue(verifier().accepts("Bearer " + validToken()));
    assertTrue(verifier().accepts("bearer " + validToken()), "Bearer 方案应大小写不敏感");
  }

  @Test
  @DisplayName("issuer 不符即拒绝（换个人签的 issuer 也算不符）")
  void rejectsWrongIssuer() throws Exception {
    String jwt =
        token(signingKey, "evil@example.com", PROJECT_NUMBER, Instant.now().plusSeconds(600));
    assertFalse(verifier().accepts("Bearer " + jwt));
  }

  @Test
  @DisplayName("audience 与本应用 project number 不符即拒绝")
  void rejectsWrongAudience() throws Exception {
    String jwt = token(signingKey, ISSUER, "999999999", Instant.now().plusSeconds(600));
    assertFalse(verifier().accepts("Bearer " + jwt));
  }

  @Test
  @DisplayName("过期 token 拒绝 —— 其余三项都对也拦")
  void rejectsExpiredToken() throws Exception {
    String jwt = token(signingKey, ISSUER, PROJECT_NUMBER, Instant.now().minusSeconds(60));
    assertFalse(verifier().accepts("Bearer " + jwt));
  }

  @Test
  @DisplayName("签名不匹配即拒绝：别的密钥签的、issuer/aud 都伪造对也没用")
  void rejectsForeignSignature() throws Exception {
    String jwt = token(foreignKey, ISSUER, PROJECT_NUMBER, Instant.now().plusSeconds(600));
    assertFalse(verifier().accepts("Bearer " + jwt), "验签是这道闸的本体");
  }

  @Test
  @DisplayName("缺 Authorization / 非 Bearer / 空 token 一律拒绝")
  void rejectsMalformedAuthorization() {
    GoogleChatCallerJwt verifier = verifier();
    assertFalse(verifier.accepts(null));
    assertFalse(verifier.accepts(""));
    assertFalse(verifier.accepts("Basic abc"));
    assertFalse(verifier.accepts("Bearer"));
    assertFalse(verifier.accepts("Bearer "));
    assertFalse(verifier.accepts("not-a-jwt"));
  }
}
