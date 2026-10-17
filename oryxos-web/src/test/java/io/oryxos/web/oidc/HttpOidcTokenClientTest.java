package io.oryxos.web.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.proc.BadJWSException;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.oryxos.web.config.WebOidcProperties;
import io.oryxos.web.oidc.HttpOidcTokenClient.OidcTokenException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * HttpOidcTokenClient 走真实 HTTP：discovery、换码、JWKS 验签全部打在本地 {@link HttpServer} 上，id_token 用自签 RSA
 * 密钥签发，不依赖外网 IdP。
 *
 * <p>断言用客户端自己的失败消息区分「这个类手写的校验」与 Nimbus 默认 verifier 的校验：例如过期 30s 的 exp（Nimbus 默认 verifier 容忍 60s
 * 偏差）断言 {@code id_token expired}，证明拒绝发生在客户端自己的 exp 校验。
 */
class HttpOidcTokenClientTest {

  private static final String CLIENT_ID = "oryxos-web";
  private static final String REDIRECT_URI = "https://app.example/api/v1/auth/oidc/callback";
  private static final String DISCOVERY_SUFFIX = "/.well-known/openid-configuration";
  private static final String TOKEN_PATH = "/token";
  private static final String TOKEN_PATH_OVERRIDE = "/token-custom";
  private static final String JWKS_PATH = "/jwks";

  private static RSAKey signingKey;
  private static RSAKey foreignKey;

  private final List<String> requestedPaths = new CopyOnWriteArrayList<>();
  private final List<String> tokenRequests = new CopyOnWriteArrayList<>();

  private HttpServer server;
  private String base;
  private String issuer;
  private WebOidcProperties properties;
  private HttpOidcTokenClient client;

  private volatile int discoveryStatus;
  private volatile String discoveryBody;
  private volatile int tokenStatus;
  private volatile String tokenBody;
  private volatile int jwksStatus;
  private volatile String jwksBody;

  @BeforeAll
  static void generateKeys() throws JOSEException {
    signingKey = new RSAKeyGenerator(2048).keyID("signing-key").generate();
    foreignKey = new RSAKeyGenerator(2048).keyID("foreign-key").generate();
  }

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    issuer = base + "/oidc";

    discoveryStatus = 200;
    discoveryBody = discoveryJson(base + "/authorize", TOKEN_PATH, JWKS_PATH);
    tokenStatus = 200;
    tokenBody = "{}";
    jwksStatus = 200;
    jwksBody = new JWKSet(signingKey.toPublicJWK()).toString();

    properties = new WebOidcProperties();
    properties.setIssuer(issuer);
    properties.setClientId(CLIENT_ID);
    properties.setRedirectUri(REDIRECT_URI);
    client = new HttpOidcTokenClient(new ObjectMapper());
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  // --- discovery / 端点解析 ---

  @Test
  @DisplayName("三处端点都已配置_不发 discovery 请求")
  void allEndpointsConfigured_skipsDiscovery() {
    discoveryStatus = 500;
    properties.setAuthorizationEndpoint(base + "/authorize");
    properties.setTokenEndpoint(base + TOKEN_PATH);
    properties.setJwksUri(base + JWKS_PATH);

    assertThat(client.resolveAuthorizationEndpoint(properties)).isEqualTo(base + "/authorize");
    assertThat(requestedPaths).isEmpty();
  }

  @Test
  @DisplayName("discovery 成功_解析 authorization_endpoint")
  void discoverySuccess_resolvesAuthorizationEndpoint() {
    assertThat(client.resolveAuthorizationEndpoint(properties)).isEqualTo(base + "/authorize");
    assertThat(requestedPaths).containsExactly("/oidc" + DISCOVERY_SUFFIX);
  }

  @Test
  @DisplayName("issuer 末尾带斜杠_discovery 路径不出现双斜杠")
  void issuerTrailingSlash_usesSingleDiscoveryPath() {
    properties.setIssuer(issuer + "/");

    assertThat(client.resolveAuthorizationEndpoint(properties)).isEqualTo(base + "/authorize");
    assertThat(requestedPaths).containsExactly("/oidc" + DISCOVERY_SUFFIX);
  }

  @Test
  @DisplayName("discovery 非 2xx_抛 discovery HTTP 状态")
  void discoveryNon2xx_throws() {
    discoveryStatus = 503;

    assertThatThrownBy(() -> client.resolveAuthorizationEndpoint(properties))
        .isInstanceOf(OidcTokenException.class)
        .hasMessage("OIDC discovery HTTP 503");
  }

  @Test
  @DisplayName("discovery body 非 JSON_包装为 OIDC discovery failed")
  void discoveryNonJson_throws() {
    discoveryBody = "<html>not json</html>";

    Throwable thrown = catchThrowable(() -> client.resolveAuthorizationEndpoint(properties));

    assertThat(thrown).isInstanceOf(OidcTokenException.class).hasMessage("OIDC discovery failed");
    assertThat(thrown.getCause()).isInstanceOf(IOException.class);
  }

  @Test
  @DisplayName("discovery 缺 jwks_uri_抛缺端点配置")
  void discoveryMissingEndpoint_throws() {
    discoveryBody =
        "{\"authorization_endpoint\":\""
            + base
            + "/authorize\",\"token_endpoint\":\""
            + base
            + TOKEN_PATH
            + "\"}";

    assertThatThrownBy(() -> client.resolveAuthorizationEndpoint(properties))
        .isInstanceOf(OidcTokenException.class)
        .hasMessage("missing OIDC endpoint configuration");
  }

  @Test
  @DisplayName("已配置的 token 端点覆盖 discovery_其余端点仍回落")
  void configuredTokenEndpointOverridesDiscovered() throws JOSEException {
    properties.setTokenEndpoint(base + TOKEN_PATH_OVERRIDE);
    tokenRespondsWith(validClaims().build());

    OidcIdTokenClaims claims = exchange();

    assertThat(claims.subject()).isEqualTo("subject-123");
    assertThat(requestedPaths).contains("/oidc" + DISCOVERY_SUFFIX, JWKS_PATH);
    assertThat(requestedPaths).contains(TOKEN_PATH_OVERRIDE).doesNotContain(TOKEN_PATH);
  }

  @Test
  @DisplayName("需要 discovery 但 issuer 为空_抛必填")
  void discoveryWithoutIssuer_throws() {
    properties.setIssuer("");

    assertThatThrownBy(() -> client.resolveAuthorizationEndpoint(properties))
        .isInstanceOf(OidcTokenException.class)
        .hasMessage("oryxos.web.oidc.issuer is required");
  }

  // --- 换码请求 ---

  @Test
  @DisplayName("换码请求_表单字段与 URL 编码；未配置 secret 时不带 client_secret")
  void exchangeSendsFormEncodedRequestWithoutSecret() throws JOSEException {
    tokenRespondsWith(validClaims().build());

    exchangeWith("a/b+c d", "verifier/+= ");

    assertThat(tokenRequests).hasSize(1);
    assertThat(tokenRequests.get(0))
        .contains("grant_type=authorization_code")
        .contains("code=a%2Fb%2Bc+d")
        .contains("client_id=" + CLIENT_ID)
        .contains("redirect_uri=https%3A%2F%2Fapp.example%2Fapi%2Fv1%2Fauth%2Foidc%2Fcallback")
        .contains("code_verifier=verifier%2F%2B%3D+")
        .doesNotContain("client_secret=");
  }

  @Test
  @DisplayName("配置了 client secret_只出现在表单 body 且被编码")
  void exchangeSendsClientSecretWhenConfigured() throws JOSEException {
    properties.setClientSecret("s3cret +&");
    tokenRespondsWith(validClaims().build());

    exchange();

    assertThat(tokenRequests).hasSize(1);
    assertThat(tokenRequests.get(0)).contains("client_secret=s3cret+%2B%26");
  }

  @Test
  @DisplayName("token 端点非 2xx_抛 token endpoint HTTP 状态")
  void tokenEndpointNon2xx_throws() {
    tokenStatus = 400;
    tokenBody = "{\"error\":\"invalid_grant\"}";

    assertThatThrownBy(this::exchange)
        .isInstanceOf(OidcTokenException.class)
        .hasMessage("OIDC token endpoint HTTP 400");
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"id_token\":\"\"}", "{\"id_token\":\"   \"}"})
  @DisplayName("token 响应缺 id_token_抛缺失")
  void tokenResponseMissingIdToken_throws(String body) {
    tokenBody = body;

    assertThatThrownBy(this::exchange)
        .isInstanceOf(OidcTokenException.class)
        .hasMessage("OIDC token response missing id_token");
  }

  @Test
  @DisplayName("token 响应非 JSON_包装为交换失败")
  void tokenResponseNonJson_throws() {
    tokenBody = "<html>oops</html>";

    Throwable thrown = catchThrowable(this::exchange);

    assertThat(thrown)
        .isInstanceOf(OidcTokenException.class)
        .hasMessage("OIDC token exchange or validation failed");
    assertThat(thrown.getCause()).isInstanceOf(IOException.class);
  }

  @Test
  @DisplayName("缺 redirect-uri_抛必填且不发 POST")
  void missingRedirectUri_throwsBeforePost() {
    properties.setRedirectUri("");

    assertThatThrownBy(this::exchange)
        .isInstanceOf(OidcTokenException.class)
        .hasMessage("oryxos.web.oidc.redirectUri is required");
    assertThat(tokenRequests).isEmpty();
  }

  @Test
  @DisplayName("缺 client-id_抛必填且不发 POST")
  void missingClientId_throwsBeforePost() {
    properties.setClientId("");

    assertThatThrownBy(this::exchange)
        .isInstanceOf(OidcTokenException.class)
        .hasMessage("oryxos.web.oidc.clientId is required");
    assertThat(tokenRequests).isEmpty();
  }

  // --- id_token 校验 ---

  @Test
  @DisplayName("合法 id_token_返回验签后的声明")
  void exchangeReturnsValidatedClaims() throws JOSEException {
    properties.setOrgIdsClaim("org_ids");
    tokenRespondsWith(
        validClaims()
            .claim("email", "alice@example.com")
            .claim("preferred_username", "alice")
            .claim("groups", List.of("team-a", " team-b ", "  "))
            .claim("org_ids", "org-1, org-2")
            .build());

    OidcIdTokenClaims claims = exchange();

    assertThat(claims.issuer()).isEqualTo(issuer);
    assertThat(claims.subject()).isEqualTo("subject-123");
    assertThat(claims.email()).isEqualTo("alice@example.com");
    assertThat(claims.preferredUsername()).isEqualTo("alice");
    assertThat(claims.groups()).containsExactly("team-a", "team-b");
    assertThat(claims.orgIds()).containsExactly("org-1", "org-2");
    assertThat(requestedPaths).containsExactly("/oidc" + DISCOVERY_SUFFIX, TOKEN_PATH, JWKS_PATH);
  }

  @Test
  @DisplayName("iss 与配置不符_抛 iss mismatch")
  void issMismatch_throws() throws JOSEException {
    tokenRespondsWith(validClaims().issuer("https://evil.example").build());

    assertThatThrownBy(this::exchange)
        .isInstanceOf(OidcTokenException.class)
        .hasMessage("id_token iss mismatch");
  }

  @Test
  @DisplayName("aud 不含 client-id_抛 aud mismatch")
  void audMismatch_throws() throws JOSEException {
    tokenRespondsWith(validClaims().audience("another-client").build());

    assertThatThrownBy(this::exchange)
        .isInstanceOf(OidcTokenException.class)
        .hasMessage("id_token aud mismatch");
  }

  @Test
  @DisplayName("缺 aud_抛 aud mismatch")
  void missingAudience_throws() throws JOSEException {
    tokenRespondsWith(claimsWithoutAudience().build());

    assertThatThrownBy(this::exchange)
        .isInstanceOf(OidcTokenException.class)
        .hasMessage("id_token aud mismatch");
  }

  @Test
  @DisplayName("exp 已过期_抛 id_token expired")
  void expiredToken_throws() throws JOSEException {
    // 过期 30s：Nimbus 默认 verifier 容忍 60s 时钟偏差，拒绝落在客户端自己的 exp 校验。
    tokenRespondsWith(
        validClaims().expirationTime(new Date(System.currentTimeMillis() - 30_000L)).build());

    assertThatThrownBy(this::exchange)
        .isInstanceOf(OidcTokenException.class)
        .hasMessage("id_token expired");
  }

  @Test
  @DisplayName("缺 exp_抛 id_token expired")
  void missingExpiration_throws() throws JOSEException {
    tokenRespondsWith(claimsWithoutExpiration().build());

    assertThatThrownBy(this::exchange)
        .isInstanceOf(OidcTokenException.class)
        .hasMessage("id_token expired");
  }

  @Test
  @DisplayName("缺 sub_抛 missing sub")
  void missingSubject_throws() throws JOSEException {
    tokenRespondsWith(claimsWithoutSubject().build());

    assertThatThrownBy(this::exchange)
        .isInstanceOf(OidcTokenException.class)
        .hasMessage("id_token missing sub");
  }

  @Test
  @DisplayName("sub 为空白_抛 missing sub")
  void blankSubject_throws() throws JOSEException {
    tokenRespondsWith(claimsWithoutSubject().subject("   ").build());

    assertThatThrownBy(this::exchange)
        .isInstanceOf(OidcTokenException.class)
        .hasMessage("id_token missing sub");
  }

  @Test
  @DisplayName("签名钥不匹配 JWKS_包装为校验失败")
  void foreignSignature_throws() throws JOSEException {
    // kid 指向 JWKS 里的签名钥，但实际用另一把钥签名：失败来自验签，而不是选钥。
    tokenBody = "{\"id_token\":\"" + sign(validClaims().build(), foreignKey) + "\"}";

    Throwable thrown = catchThrowable(this::exchange);

    assertThat(thrown)
        .isInstanceOf(OidcTokenException.class)
        .hasMessage("OIDC token exchange or validation failed");
    assertThat(thrown.getCause()).isInstanceOf(BadJWSException.class);
  }

  @Test
  @DisplayName("JWKS 端点非 2xx_包装为校验失败")
  void jwksUnavailable_throws() throws JOSEException {
    jwksStatus = 500;
    jwksBody = "";
    tokenRespondsWith(validClaims().build());

    Throwable thrown = catchThrowable(this::exchange);

    assertThat(thrown)
        .isInstanceOf(OidcTokenException.class)
        .hasMessage("OIDC token exchange or validation failed");
    assertThat(thrown.getCause()).isInstanceOf(JOSEException.class);
  }

  @Test
  @DisplayName("group-claim 配置名决定读哪个声明")
  void configuredGroupClaimIsRead() throws JOSEException {
    properties.setGroupClaim("roles");
    tokenRespondsWith(
        validClaims()
            .claim("roles", List.of("admin"))
            .claim("groups", List.of("should-be-ignored"))
            .build());

    assertThat(exchange().groups()).containsExactly("admin");
  }

  @Test
  @DisplayName("未配置 org-ids-claim_不提取声明")
  void orgIdsClaimNotConfigured_isIgnored() throws JOSEException {
    tokenRespondsWith(validClaims().claim("org_ids", List.of("org-1")).build());

    assertThat(exchange().orgIds()).isEmpty();
  }

  @Test
  @DisplayName("缺可选声明_返回 null 与空列表")
  void optionalClaimsAbsent_returnsNullsAndEmptyLists() throws JOSEException {
    tokenRespondsWith(validClaims().build());

    OidcIdTokenClaims claims = exchange();

    assertThat(claims.email()).isNull();
    assertThat(claims.preferredUsername()).isNull();
    assertThat(claims.groups()).isEmpty();
    assertThat(claims.orgIds()).isEmpty();
  }

  // --- 夹具 ---

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    requestedPaths.add(path);
    if (path.endsWith(DISCOVERY_SUFFIX)) {
      respond(exchange, discoveryStatus, discoveryBody);
    } else if (TOKEN_PATH.equals(path) || TOKEN_PATH_OVERRIDE.equals(path)) {
      tokenRequests.add(
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, tokenStatus, tokenBody);
    } else if (JWKS_PATH.equals(path)) {
      respond(exchange, jwksStatus, jwksBody);
    } else {
      respond(exchange, 404, "{\"error\":\"not_found\"}");
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    if (bytes.length == 0) {
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
      return;
    }
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private String discoveryJson(String authorizationEndpoint, String tokenPath, String jwksPath) {
    return "{\"authorization_endpoint\":\""
        + authorizationEndpoint
        + "\",\"token_endpoint\":\""
        + base
        + tokenPath
        + "\",\"jwks_uri\":\""
        + base
        + jwksPath
        + "\"}";
  }

  private JWTClaimsSet.Builder claimsWithoutAudience() {
    return new JWTClaimsSet.Builder()
        .issuer(issuer)
        .subject("subject-123")
        .expirationTime(new Date(System.currentTimeMillis() + 300_000L));
  }

  private JWTClaimsSet.Builder validClaims() {
    return claimsWithoutAudience().audience(CLIENT_ID);
  }

  private JWTClaimsSet.Builder claimsWithoutExpiration() {
    return new JWTClaimsSet.Builder().issuer(issuer).subject("subject-123").audience(CLIENT_ID);
  }

  private JWTClaimsSet.Builder claimsWithoutSubject() {
    return new JWTClaimsSet.Builder()
        .issuer(issuer)
        .audience(CLIENT_ID)
        .expirationTime(new Date(System.currentTimeMillis() + 300_000L));
  }

  private String sign(JWTClaimsSet claims) throws JOSEException {
    return sign(claims, signingKey);
  }

  /** kid 始终取 JWKS 里那把钥，{@code key} 决定实际签名私钥。 */
  private String sign(JWTClaimsSet claims, RSAKey key) throws JOSEException {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private void tokenRespondsWith(JWTClaimsSet claims) throws JOSEException {
    tokenBody = "{\"id_token\":\"" + sign(claims) + "\"}";
  }

  private OidcIdTokenClaims exchange() {
    return exchangeWith("auth-code", "code-verifier");
  }

  private OidcIdTokenClaims exchangeWith(String code, String codeVerifier) {
    return client.exchangeAndValidate(code, codeVerifier, properties);
  }
}
