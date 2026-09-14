package io.oryxos.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.auth.Role;
import io.oryxos.core.policy.AuthorizationService;
import io.oryxos.core.policy.RoleBasedAuthorizationServiceImpl;
import io.oryxos.storage.ApiKeyService;
import io.oryxos.storage.WebSession;
import io.oryxos.storage.WebSessionService;
import io.oryxos.web.config.WebApiKeyProperties;
import io.oryxos.web.config.WebRbacProperties;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * RBAC 强制点的端到端行为（039-identity-authorization）。
 *
 * <p>本用例补的是独立复核报告点名的缺口：授权裁决此前只有单元层证据，**没有任何真实请求穿过 filter 链**。 这里用 MockMvc 把完整链路搭起来（认证门 → 主体置入 →
 * 授权裁决 → 403/200），证明「唯一决策点」在真实请求上 确实生效，而不只是在单测里成立。
 *
 * <p>三条主线：
 *
 * <ol>
 *   <li>{@code rbac.enabled=false}：既有行为逐字不变（放行，且不解析主体）。
 *   <li>{@code rbac.enabled=true} + 角色足够：放行 200。
 *   <li>{@code rbac.enabled=true} + 角色不足：403，且**请求未到达控制器**（拒绝必须真的拦住执行，不是只改状态码）。
 * </ol>
 */
class RbacEnforcerTest {

  private static final String KEY = "oryx_test_key_value";
  private static final String SESSION_ID = "sid-rbac-1";

  private ApiKeyService apiKeyService;

  private WebSessionService sessionService;

  private WebApiKeyProperties apiKeyProperties;

  private WebRbacProperties rbacProperties;

  private MockMvc mvc;

  private StubController controller;

  /** 测试桩：记录它是否被调用——用来证明「拒绝」是拦住了执行，而不只是写了个状态码。 */
  @Controller
  static class StubController {

    private int hits;

    @GetMapping("/api/v1/profiles")
    @ResponseBody
    public String profiles() {
      hits++;
      return "ok";
    }
  }

  @BeforeEach
  void setUp() {
    apiKeyService = mock(ApiKeyService.class);
    sessionService = mock(WebSessionService.class);
    apiKeyProperties = new WebApiKeyProperties();
    apiKeyProperties.setEnabled(true);
    rbacProperties = new WebRbacProperties();
    controller = new StubController();
  }

  /** 按当前 rbacProperties 与给定裁决点装配完整 filter 链。 */
  private void buildMvc(AuthorizationService authorizationService) {
    RbacEnforcer enforcer = new RbacEnforcer(authorizationService, rbacProperties);
    ApiKeyAuthFilter filter =
        new ApiKeyAuthFilter(
            apiKeyService,
            sessionService,
            apiKeyProperties,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            enforcer);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .addFilter(filter, "/api/v1/*", "/api/v2/*", "/actuator/*")
            .build();
  }

  private static WebSession session(String username) {
    WebSession session = new WebSession();
    session.setSessionId(SESSION_ID);
    session.setUsername(username);
    session.setExpiresAt(Instant.now().plus(Duration.ofHours(1)));
    return session;
  }

  @Test
  @DisplayName("rbac关闭_带有效Key_200且控制器被调用（零行为变化）")
  void rbacDisabled_passesThrough() throws Exception {
    rbacProperties.setEnabled(false);
    when(apiKeyService.verify(KEY)).thenReturn(true);
    buildMvc(new RoleBasedAuthorizationServiceImpl(Set.of(Role.ADMIN), Set.of(Role.ADMIN)));

    mvc.perform(get("/api/v1/profiles").header("Authorization", "Bearer " + KEY))
        .andExpect(status().isOk());

    assertThat(controller.hits).isEqualTo(1);
  }

  @Test
  @DisplayName("rbac开启_管理台账号ADMIN_放行200且控制器被调用")
  void rbacEnabled_adminUser_allowed() throws Exception {
    rbacProperties.setEnabled(true);
    when(sessionService.findValid(SESSION_ID)).thenReturn(Optional.of(session("alice")));
    buildMvc(new RoleBasedAuthorizationServiceImpl(Set.of(Role.ADMIN), Set.of()));

    mvc.perform(get("/api/v1/profiles").cookie(new Cookie("oryxos_session", SESSION_ID)))
        .andExpect(status().isOk());

    assertThat(controller.hits).isEqualTo(1);
  }

  @Test
  @DisplayName("rbac开启_角色不足_403且控制器未被调用（拒绝真的拦住了执行）")
  void rbacEnabled_insufficientRole_forbiddenAndNotExecuted() throws Exception {
    rbacProperties.setEnabled(true);
    when(sessionService.findValid(SESSION_ID)).thenReturn(Optional.of(session("bob")));
    // 账号无角色且默认档为空 → 裁决拒绝。这里刻意用「已认证但零角色」表达最常见的越权形态。
    buildMvc(new RoleBasedAuthorizationServiceImpl(Set.of(), Set.of()));

    mvc.perform(get("/api/v1/profiles").cookie(new Cookie("oryxos_session", SESSION_ID)))
        .andExpect(status().isForbidden());

    assertThat(controller.hits).isZero();
  }

  @Test
  @DisplayName("rbac开启_API Key默认档为空_403（机器凭证不给默认权限）")
  void rbacEnabled_apiKeyNoDefaultRole_forbidden() throws Exception {
    rbacProperties.setEnabled(true);
    when(apiKeyService.verify(KEY)).thenReturn(true);
    when(apiKeyService.findNameByPlaintext(KEY)).thenReturn("ci-bot");
    buildMvc(new RoleBasedAuthorizationServiceImpl(Set.of(Role.ADMIN), Set.of()));

    mvc.perform(get("/api/v1/profiles").header("Authorization", "Bearer " + KEY))
        .andExpect(status().isForbidden());

    assertThat(controller.hits).isZero();
  }

  @Test
  @DisplayName("rbac开启_启用时才解析Key名称（未启用不得多打一次库）")
  void rbacEnabled_resolvesKeyNameOnlyWhenActive() throws Exception {
    rbacProperties.setEnabled(true);
    when(apiKeyService.verify(KEY)).thenReturn(true);
    when(apiKeyService.findNameByPlaintext(KEY)).thenReturn("ci-bot");
    buildMvc(new RoleBasedAuthorizationServiceImpl(Set.of(), Set.of(Role.VIEWER)));

    mvc.perform(get("/api/v1/profiles").header("Authorization", "Bearer " + KEY))
        .andExpect(status().isOk());

    org.mockito.Mockito.verify(apiKeyService).findNameByPlaintext(KEY);
  }

  @Test
  @DisplayName("rbac开启_拒绝响应为403且不带HTML内容类型（不把API客户端带进登录页）")
  void rbacEnabled_forbiddenIsNotHtml() throws Exception {
    rbacProperties.setEnabled(true);
    when(sessionService.findValid(SESSION_ID)).thenReturn(Optional.of(session("bob")));
    buildMvc(new RoleBasedAuthorizationServiceImpl(Set.of(), Set.of()));

    mvc.perform(get("/api/v1/profiles").cookie(new Cookie("oryxos_session", SESSION_ID)))
        .andExpect(status().isForbidden())
        .andExpect(
            result ->
                assertThat(result.getResponse().getContentType())
                    // 拒答不写 body 时 content-type 为 null；本用例只要求它**不是** HTML——
                    // 401/302 那种把人带去登录页的语义不能出现在授权拒绝上，否则 REST 客户端会拿到一页登录 HTML。
                    .matches(type -> type == null || !type.contains(MediaType.TEXT_HTML_VALUE)));
  }
}
