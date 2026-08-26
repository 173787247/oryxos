package io.oryxos.web.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.storage.ApiKeyService;
import io.oryxos.storage.WebSession;
import io.oryxos.storage.WebSessionService;
import io.oryxos.web.config.WebApiKeyProperties;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 018 验收 harness：ApiKeyAuthFilterTest——路径裁决表钉死（contracts/auth-contract.md §1）。用 standalone MockMvc
 * + stub controller 映射 /api/v1/**（filter 放行后要有 handler），按注册模式 addFilter(filter, "/api/v1/*")。mock
 * ApiKeyService/WebSessionService。守：flag 关放行、双请求头等效、401 统一响应不可区分、 豁免（OPTIONS/health/auth
 * 子树）、session 互认、多 Key 吊销互不影响。
 */
class ApiKeyAuthFilterTest {

  private static final String GOOD_KEY = "oryx_" + "A".repeat(42);
  private static final String BAD_KEY = "oryx_" + "B".repeat(42);

  private ApiKeyService apiKeyService;
  private WebSessionService sessionService;
  private WebApiKeyProperties properties;
  private MockMvc mvc;

  @Controller
  static class StubController {
    @GetMapping("/api/v1/profiles")
    @ResponseBody
    public String profiles() {
      return "ok";
    }

    @GetMapping("/api/v1/health")
    @ResponseBody
    public String health() {
      return "up";
    }

    @PostMapping("/api/v1/auth/login")
    @ResponseBody
    public String login() {
      return "login";
    }
  }

  @BeforeEach
  void setUp() {
    apiKeyService = mock(ApiKeyService.class);
    sessionService = mock(WebSessionService.class);
    properties = new WebApiKeyProperties();
    ApiKeyAuthFilter filter =
        new ApiKeyAuthFilter(apiKeyService, sessionService, properties, new ObjectMapper());
    mvc =
        MockMvcBuilders.standaloneSetup(new StubController())
            .addFilter(filter, "/api/v1/*")
            .build();
  }

  @Test
  @DisplayName("enabled=false_无凭据放行_200（SC-001回归零破坏）")
  void disabled_passesThrough() throws Exception {
    properties.setEnabled(false);
    mvc.perform(get("/api/v1/profiles")).andExpect(status().isOk());
    verify(apiKeyService, never()).verify(anyString());
  }

  @Test
  @DisplayName("enabled=true_无凭据_401+Bearer挑战头+统一信封")
  void enabledNoCredentials_401() throws Exception {
    properties.setEnabled(true);
    mvc.perform(get("/api/v1/profiles"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"OryxOS\""))
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("Unauthorized"));
  }

  @Test
  @DisplayName("enabled=true_Bearer正确Key_200")
  void enabledBearerCorrectKey_200() throws Exception {
    properties.setEnabled(true);
    when(apiKeyService.verify(GOOD_KEY)).thenReturn(true);

    mvc.perform(get("/api/v1/profiles").header("Authorization", "Bearer " + GOOD_KEY))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("enabled=true_X-API-Key正确Key_200（双写法等效）")
  void enabledHeaderCorrectKey_200() throws Exception {
    properties.setEnabled(true);
    when(apiKeyService.verify(GOOD_KEY)).thenReturn(true);

    mvc.perform(get("/api/v1/profiles").header("X-API-Key", GOOD_KEY)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("enabled=true_错误Key与无Key_响应体完全一致（防探测）")
  void enabledWrongKey_indistinguishableFromNoKey() throws Exception {
    properties.setEnabled(true);
    when(apiKeyService.verify(anyString())).thenReturn(false);

    String noKeyBody =
        mvc.perform(get("/api/v1/profiles"))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String wrongKeyBody =
        mvc.perform(get("/api/v1/profiles").header("X-API-Key", BAD_KEY))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String revokedKeyBody =
        mvc.perform(get("/api/v1/profiles").header("Authorization", "Bearer " + BAD_KEY))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // 剥离 timestamp 后逐字节一致：无 Key / 错 Key / 已吊销（verify 均 false）不可区分
    org.assertj.core.api.Assertions.assertThat(stripTimestamp(wrongKeyBody))
        .isEqualTo(stripTimestamp(noKeyBody));
    org.assertj.core.api.Assertions.assertThat(stripTimestamp(revokedKeyBody))
        .isEqualTo(stripTimestamp(noKeyBody));
  }

  @Test
  @DisplayName("enabled=true_多Key并存_吊销A后A被拒B仍通过（SC-004）")
  void multiKey_revokeOneOtherUnaffected() throws Exception {
    properties.setEnabled(true);
    // 吊销后 service.verify(A)=false、verify(B)=true——filter 面只信 verify 裁决
    when(apiKeyService.verify(GOOD_KEY)).thenReturn(true);
    when(apiKeyService.verify(BAD_KEY)).thenReturn(false);

    mvc.perform(get("/api/v1/profiles").header("X-API-Key", GOOD_KEY)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/profiles").header("X-API-Key", BAD_KEY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("OPTIONS预检_无凭据放行（FR-014）")
  void optionsPreflight_exempt() throws Exception {
    properties.setEnabled(true);
    mvc.perform(options("/api/v1/profiles")).andExpect(status().isOk());
    verify(apiKeyService, never()).verify(anyString());
  }

  @Test
  @DisplayName("/api/v1/health_无凭据放行（探活豁免）")
  void health_exempt() throws Exception {
    properties.setEnabled(true);
    mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andExpect(content().string("up"));
  }

  @Test
  @DisplayName("/api/v1/auth/*子树_无凭据放行（012现状）")
  void authSubtree_exempt() throws Exception {
    properties.setEnabled(true);
    mvc.perform(post("/api/v1/auth/login")).andExpect(status().isOk());
    verify(apiKeyService, never()).verify(anyString());
  }

  @Test
  @DisplayName("enabled=true_有效管理台session无Key_放行（FR-011互认）")
  void validSessionNoKey_passes() throws Exception {
    properties.setEnabled(true);
    WebSession session = new WebSession();
    session.setSessionId("sid-1");
    session.setUsername("admin");
    session.setExpiresAt(Instant.now().plusSeconds(3600));
    when(sessionService.findValid("sid-1")).thenReturn(Optional.of(session));

    mvc.perform(
            get("/api/v1/profiles")
                .cookie(new jakarta.servlet.http.Cookie("oryxos_session", "sid-1")))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("enabled=true_无效session无Key_401")
  void invalidSessionNoKey_401() throws Exception {
    properties.setEnabled(true);
    when(sessionService.findValid("sid-x")).thenReturn(Optional.empty());

    mvc.perform(
            get("/api/v1/profiles")
                .cookie(new jakarta.servlet.http.Cookie("oryxos_session", "sid-x")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("enabled=true_Basic头（非Bearer）无Key_401")
  void basicHeaderNotAccepted_401() throws Exception {
    properties.setEnabled(true);
    mvc.perform(get("/api/v1/profiles").header("Authorization", "Basic dXNlcjpwdw=="))
        .andExpect(status().isUnauthorized());
    verify(apiKeyService, never()).verify(anyString());
  }

  private static String stripTimestamp(String body) {
    return body.replaceAll("\"timestamp\":\\d+", "\"timestamp\":0");
  }
}
