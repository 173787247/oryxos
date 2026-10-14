package io.oryxos.channel.teams;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.oryxos.core.channel.InboundMediaPaths;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bot Framework 的**官方**调用方校验：验证其签发的 JWT。
 *
 * <p>Azure Bot Service 要求校验 {@code Authorization} 头里的 JWT —— issuer 为 {@code
 * https://api.botframework.com}、audience 为 Bot 的 {@code appId}、签名由 {@code login.botframework.com}
 * 公布的 JWKS 验证。这是官方方式，比 {@code extra.inbound_token}（共享密钥） 强：它证明调用方**是 Bot Framework**。
 *
 * <p>由 {@code extra.verify_caller_jwt=true} 显式开启 —— 与渠道既有的「默认关、显式开启」纪律一致。
 *
 * <p>注意：走 Azure AD 签发的部署，token 的 issuer 可能是 {@code https://sts.windows.net/<tenant>/} 而非 {@code
 * api.botframework.com}；那种部署暂不适用本校验，仍应依赖边缘。
 */
final class TeamsCallerJwt {

  static final String EXTRA_VERIFY_CALLER_JWT = "verify_caller_jwt";

  private static final Logger LOG = LoggerFactory.getLogger(TeamsCallerJwt.class);
  private static final String ISSUER = "https://api.botframework.com";
  private static final String JWKS_URI = "https://login.botframework.com/v1/.well-known/keys";
  private static final String BEARER_PREFIX = "bearer ";

  private final String appId;
  private final ConfigurableJWTProcessor<SecurityContext> processor;

  TeamsCallerJwt(String appId) {
    this(appId, remoteKeySource());
  }

  /** 测试用：注入本地 JWKS，避免打网络。 */
  TeamsCallerJwt(String appId, JWKSource<SecurityContext> keySource) {
    this.appId = appId;
    ConfigurableJWTProcessor<SecurityContext> configured = new DefaultJWTProcessor<>();
    JWSKeySelector<SecurityContext> keySelector =
        new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);
    configured.setJWSKeySelector(keySelector);
    this.processor = configured;
  }

  private static JWKSource<SecurityContext> remoteKeySource() {
    try {
      return new RemoteJWKSet<>(new URI(JWKS_URI).toURL());
    } catch (URISyntaxException | java.net.MalformedURLException e) {
      throw new IllegalStateException("Bot Framework JWKS 地址非法: " + JWKS_URI, e);
    }
  }

  /**
   * @param authorizationHeader 入站请求的 {@code Authorization} 头
   * @return 头部携带的 JWT 合法时为 {@code true}
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "iss 与 aud 来自请求，入日志前都经 InboundMediaPaths.sanitizeLog 去掉 CR/LF")
  boolean accepts(String authorizationHeader) {
    String token = bearerToken(authorizationHeader);
    if (token == null) {
      LOG.debug("Teams 调用方校验：缺少 Bearer token");
      return false;
    }
    try {
      JWTClaimsSet claims = processor.process(token, null);
      if (!ISSUER.equals(claims.getIssuer())) {
        // 实际值来自请求，入日志前去掉 CR/LF（复用共享口径，不新开一个 sanitize 副本）
        LOG.debug("Teams 调用方校验：issuer 不符，实际={}", InboundMediaPaths.sanitizeLog(claims.getIssuer()));
        return false;
      }
      List<String> audience = claims.getAudience();
      if (audience == null || audience.stream().noneMatch(appId::equals)) {
        LOG.debug(
            "Teams 调用方校验：audience 与 appId 不符，实际={}",
            InboundMediaPaths.sanitizeLog(String.valueOf(audience)));
        return false;
      }
      Date expiration = claims.getExpirationTime();
      if (expiration == null || expiration.before(new Date())) {
        LOG.debug("Teams 调用方校验：token 已过期或缺少 exp");
        return false;
      }
      return true;
    } catch (Exception e) {
      LOG.debug("Teams 调用方校验：JWT 未通过（{}）", e.getClass().getSimpleName());
      return false;
    }
  }

  private static String bearerToken(String authorizationHeader) {
    if (authorizationHeader == null) {
      return null;
    }
    String value = authorizationHeader.trim();
    if (!value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
      return null;
    }
    String token = value.substring(BEARER_PREFIX.length()).trim();
    return token.isEmpty() ? null : token;
  }
}
