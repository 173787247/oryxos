package io.oryxos.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.cost.BudgetExceededException;
import io.oryxos.core.routing.ResidencyConstraintException;
import io.oryxos.core.session.SessionUpdateConflictException;
import io.oryxos.provider.ProviderNotFoundException;
import io.oryxos.web.error.AgentTimeoutException;
import io.oryxos.web.error.ProviderUnavailableException;
import io.oryxos.web.error.ResourceNotFoundException;
import io.oryxos.web.error.SessionNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 课件《第26节》验收 harness：GlobalExceptionHandlerTest——每类异常映射到约定状态码、响应体都是统一 ApiResponse， 关键回归"内部异常细节绝不出现在
 * 500 响应里"。用一个内联抛异常的 dummy controller + 真 advice 跑 standalone MockMvc。
 */
class GlobalExceptionHandlerTest {

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("非法参数_映射400且统一信封")
  void badRequest_maps400() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/throw/bad"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("参数不对"))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  @DisplayName("会话/资源不存在_映射404")
  void notFound_maps404() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/throw/session"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
    mvc.perform(MockMvcRequestBuilders.get("/throw/resource"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("Provider故障_映射503")
  void providerDown_maps503() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/throw/provider"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(503));
  }

  @Test
  @DisplayName("Agent调用超时_映射504")
  void timeout_maps504() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/throw/timeout"))
        .andExpect(status().isGatewayTimeout())
        .andExpect(jsonPath("$.code").value(504));
  }

  @Test
  @DisplayName("会话旧快照冲突_映射409并提示重新获取")
  void sessionUpdateConflictMaps409() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/throw/session-conflict"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value(containsString("重新获取")));
  }

  @Test
  @DisplayName("内部异常细节_绝不能出现在500响应里")
  void internalDetailsNeverLeakIn500() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/throw/internal"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value(500))
        .andExpect(jsonPath("$.message").value("Internal server error")) // 统一话术
        .andExpect(content().string(not(containsString("jdbc:sqlite")))); // 连接串一个字不漏
  }

  @Test
  @DisplayName("预算拒绝_映射402（不是 429 限流），message 带着预算 reason")
  void budgetExceeded_maps402() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/throw/budget"))
        .andExpect(status().isPaymentRequired())
        .andExpect(jsonPath("$.code").value(402))
        .andExpect(jsonPath("$.message").value(containsString("月度额度耗尽")));
  }

  @Test
  @DisplayName("provider 未注册_映射400（确定性配置错误，客户端不该重试），message 点名 provider")
  void providerNotRegistered_maps400() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/throw/provider-missing"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value(containsString("未知的 provider")))
        .andExpect(jsonPath("$.message").value(containsString("deepseek")));
  }

  @Test
  @DisplayName("驻留白名单拒绝_映射403（策略拒绝，不是服务端故障）")
  void residencyConstraint_maps403() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/throw/residency"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403))
        .andExpect(jsonPath("$.message").value(containsString("residency")));
  }

  @Test
  @DisplayName("未知异常_仍是 500 + 恰好 Internal server error：遮蔽作用由这条断言守着")
  void unknownException_staysOpaque500() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/throw/internal"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value(500))
        .andExpect(jsonPath("$.message").value("Internal server error"))
        .andExpect(content().string(not(containsString("RuntimeException"))))
        .andExpect(content().string(not(containsString("jdbc:sqlite"))))
        .andExpect(content().string(not(containsString("at io.oryxos"))));
  }

  @Test
  @DisplayName("请求体JSON语法错/缺失_映射400而非500（Spring MVC 客户端错误不再落 catch-all）")
  void malformedJson_maps400() throws Exception {
    mvc.perform(
            MockMvcRequestBuilders.post("/throw/echo")
                .contentType("application/json")
                .content("{bad json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("参数类型不匹配_映射400而非500")
  void typeMismatch_maps400() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/throw/typed").param("limit", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  /** 内联 dummy：每个路径抛一种异常，交给真 GlobalExceptionHandler 翻译。 */
  @RestController
  static class ThrowingController {
    @GetMapping("/throw/bad")
    void bad() {
      throw new IllegalArgumentException("参数不对");
    }

    @GetMapping("/throw/session")
    void session() {
      throw new SessionNotFoundException("s-x");
    }

    @GetMapping("/throw/resource")
    void resource() {
      throw new ResourceNotFoundException("Agent 不存在: x");
    }

    @GetMapping("/throw/provider")
    void provider() {
      throw new ProviderUnavailableException("deepseek 不可用");
    }

    @GetMapping("/throw/timeout")
    void timeout() {
      throw new AgentTimeoutException("Agent 调用超过 60 秒");
    }

    @GetMapping("/throw/session-conflict")
    void sessionConflict() {
      throw new SessionUpdateConflictException("s-x");
    }

    @GetMapping("/throw/budget")
    void budget() {
      throw new BudgetExceededException("budget exceeded: team t-1 月度额度耗尽");
    }

    @GetMapping("/throw/provider-missing")
    void providerMissing() {
      throw new ProviderNotFoundException("deepseek");
    }

    @GetMapping("/throw/residency")
    void residency() {
      throw new ResidencyConstraintException(
          "no Agent candidate satisfies data residency allowlist for sensitive data");
    }

    @GetMapping("/throw/internal")
    void internal() {
      throw new RuntimeException("jdbc:sqlite:/data/oryxos.db connect failed");
    }

    @org.springframework.web.bind.annotation.PostMapping("/throw/echo")
    String echo(
        @org.springframework.web.bind.annotation.RequestBody java.util.Map<String, String> body) {
      return "ok";
    }

    @GetMapping("/throw/typed")
    String typed(@org.springframework.web.bind.annotation.RequestParam int limit) {
      return "ok";
    }
  }
}
