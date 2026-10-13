package io.oryxos.channel.teams;

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
 * 钉住 Bot Framework 官方 JWT 校验的四项判据：签名、issuer、audience（= 本 Bot 的 appId）、exp。
 *
 * <p>用本地生成的 RSA 密钥签真 JWT（JWKS 用 {@code ImmutableJWKSet} 注入），不打网络。
 */
class TeamsCallerJwtTest {

  private static final String ISSUER = "https://api.botframework.com";
  private static final String APP_ID = "bot-app-id";

  private static RSAKey signingKey;
  private static RSAKey foreignKey;
  private static JWKSource<SecurityContext> keySource;

  @BeforeAll
  static void generateKeys() throws Exception {
    signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
    foreignKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
    keySource = new ImmutableJWKSet<>(new JWKSet(signingKey.toPublicJWK()));
  }

  private static TeamsCallerJwt verifier() {
    return new TeamsCallerJwt(APP_ID, keySource);
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
    return token(signingKey, ISSUER, APP_ID, Instant.now().plusSeconds(600));
  }

  @Test
  @DisplayName("合法 JWT 通过")
  void acceptsValidToken() throws Exception {
    assertTrue(verifier().accepts("Bearer " + validToken()));
    assertTrue(verifier().accepts("bearer " + validToken()), "Bearer 方案应大小写不敏感");
  }

  @Test
  @DisplayName("issuer 不符即拒绝 —— 含 Azure AD 那套 issuer（本校验不覆盖）")
  void rejectsWrongIssuer() throws Exception {
    String generic =
        token(signingKey, "https://evil.example.com", APP_ID, Instant.now().plusSeconds(600));
    assertFalse(verifier().accepts("Bearer " + generic));

    String azureAd =
        token(
            signingKey,
            "https://sts.windows.net/tenant-id/",
            APP_ID,
            Instant.now().plusSeconds(600));
    assertFalse(verifier().accepts("Bearer " + azureAd), "Azure AD 签发的 token 不是本校验的目标，应拒绝而不是放行");
  }

  @Test
  @DisplayName("audience 与本 Bot 的 appId 不符即拒绝")
  void rejectsWrongAudience() throws Exception {
    String jwt = token(signingKey, ISSUER, "someone-elses-bot", Instant.now().plusSeconds(600));
    assertFalse(verifier().accepts("Bearer " + jwt), "别的 Bot 的 token 不该能驱动本 Bot");
  }

  @Test
  @DisplayName("过期 token 拒绝")
  void rejectsExpiredToken() throws Exception {
    String jwt = token(signingKey, ISSUER, APP_ID, Instant.now().minusSeconds(60));
    assertFalse(verifier().accepts("Bearer " + jwt));
  }

  @Test
  @DisplayName("签名不匹配即拒绝：别的密钥签的、issuer/aud 都伪造对也没用")
  void rejectsForeignSignature() throws Exception {
    String jwt = token(foreignKey, ISSUER, APP_ID, Instant.now().plusSeconds(600));
    assertFalse(verifier().accepts("Bearer " + jwt), "验签是这道闸的本体");
  }

  @Test
  @DisplayName("缺 Authorization / 非 Bearer / 空 token 一律拒绝")
  void rejectsMalformedAuthorization() {
    TeamsCallerJwt verifier = verifier();
    assertFalse(verifier.accepts(null));
    assertFalse(verifier.accepts(""));
    assertFalse(verifier.accepts("Basic abc"));
    assertFalse(verifier.accepts("Bearer"));
    assertFalse(verifier.accepts("Bearer "));
    assertFalse(verifier.accepts("not-a-jwt"));
  }
}
