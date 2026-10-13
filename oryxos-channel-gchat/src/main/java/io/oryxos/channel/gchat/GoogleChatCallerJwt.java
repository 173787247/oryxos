package io.oryxos.channel.gchat;

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
 * Google Chat 的**官方**调用方校验：验证 Google 签发的 JWT。
 *
 * <p>Google 要求校验 {@code Authorization} 头里的 JWT —— issuer 为 {@code
 * chat@system.gserviceaccount.com}、audience 为应用的 project number、签名由 Google 公布的 JWKS 验证。 这是官方方式，比
 * {@code extra.inbound_token}（共享密钥，只能证明持有token）强：它证明调用方**是 Google**。
 *
 * <p>仅在配置了 {@code extra.project_number} 时启用 —— 与渠道既有的「默认关、显式开启」纪律一致，不改变任何 既有部署的行为。
 *
 * <p>JWKS 由 {@link RemoteJWKSet} 拉取并内部缓存（默认 5 分钟），不必每次请求都取。
 */
final class GoogleChatCallerJwt {

  static final String EXTRA_PROJECT_NUMBER = "project_number";

  private static final Logger LOG = LoggerFactory.getLogger(GoogleChatCallerJwt.class);
  private static final String ISSUER = "chat@system.gserviceaccount.com";
  private static final String JWKS_URI =
      "https://www.googleapis.com/service_accounts/v1/jwk/" + ISSUER;
  private static final String BEARER_PREFIX = "bearer ";

  private final String projectNumber;
  private final ConfigurableJWTProcessor<SecurityContext> processor;

  GoogleChatCallerJwt(String projectNumber) {
    this(projectNumber, remoteKeySource());
  }

  /** 测试用：注入本地 JWKS，避免打网络。 */
  GoogleChatCallerJwt(String projectNumber, JWKSource<SecurityContext> keySource) {
    this.projectNumber = projectNumber;
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
      // 常量 URL，走到这里说明代码被改坏了 —— 启动期就炸，别留到第一个请求
      throw new IllegalStateException("Google Chat JWKS 地址非法: " + JWKS_URI, e);
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
      LOG.debug("Google Chat 调用方校验：缺少 Bearer token");
      return false;
    }
    try {
      JWTClaimsSet claims = processor.process(token, null);
      if (!ISSUER.equals(claims.getIssuer())) {
        // 实际值来自请求，入日志前去掉 CR/LF（复用共享口径，不新开一个 sanitize 副本）
        LOG.debug(
            "Google Chat 调用方校验：issuer 不符，实际={}", InboundMediaPaths.sanitizeLog(claims.getIssuer()));
        return false;
      }
      List<String> audience = claims.getAudience();
      if (audience == null || audience.stream().noneMatch(projectNumber::equals)) {
        LOG.debug(
            "Google Chat 调用方校验：audience 不符，实际={}",
            InboundMediaPaths.sanitizeLog(String.valueOf(audience)));
        return false;
      }
      Date expiration = claims.getExpirationTime();
      if (expiration == null || expiration.before(new Date())) {
        LOG.debug("Google Chat 调用方校验：token 已过期或缺少 exp");
        return false;
      }
      return true;
    } catch (Exception e) {
      // 验签失败、格式错误、JWKS 拉取失败都归为"未通过"——不把内部细节回给调用方
      LOG.debug("Google Chat 调用方校验：JWT 未通过（{}）", e.getClass().getSimpleName());
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
