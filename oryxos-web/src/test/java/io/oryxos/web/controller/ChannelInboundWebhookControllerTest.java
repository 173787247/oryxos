package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundChannelRegistry;
import io.oryxos.core.channel.InboundWebhookHandler;
import io.oryxos.core.channel.WebhookRequest;
import io.oryxos.core.channel.WebhookResponse;
import io.oryxos.web.GlobalExceptionHandler;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 026 P0：未知渠道 / 非 webhook 适配器 → 404；实现了 handler 的渠道原样回写挑战体。 */
class ChannelInboundWebhookControllerTest {

  private InboundChannelRegistry registry;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    registry = mock(InboundChannelRegistry.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new ChannelInboundWebhookController(registry))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("未注册渠道 webhook：404")
  void unknownChannelReturns404() throws Exception {
    when(registry.get("ghost")).thenReturn(Optional.empty());
    mvc.perform(
            post("/api/v1/channels/inbound/ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("已上线但不实现 InboundWebhookHandler：404")
  void nonWebhookAdapterReturns404() throws Exception {
    InboundChannelAdapter adapter = mock(InboundChannelAdapter.class);
    when(registry.get("ops-feishu")).thenReturn(Optional.of(adapter));
    mvc.perform(post("/api/v1/channels/inbound/ops-feishu").content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("GET 挑战握手：原样回写 challenge 文本")
  void webhookChallengePassthrough() throws Exception {
    WebhookChannel adapter = mock(WebhookChannel.class);
    when(adapter.onWebhook(any(WebhookRequest.class)))
        .thenReturn(WebhookResponse.text(200, "challenge-token"));
    when(registry.get("ops-wa")).thenReturn(Optional.of(adapter));

    mvc.perform(
            get("/api/v1/channels/inbound/ops-wa")
                .param("hub.mode", "subscribe")
                .param("hub.verify_token", "secret")
                .param("hub.challenge", "challenge-token"))
        .andExpect(status().isOk())
        .andExpect(content().string("challenge-token"));
    verify(adapter).onWebhook(any(WebhookRequest.class));
  }

  /** 测试双接口桩：入站适配器 + webhook。 */
  private interface WebhookChannel extends InboundChannelAdapter, InboundWebhookHandler {
    @Override
    default String name() {
      return "ops-wa";
    }

    @Override
    default String type() {
      return "whatsapp";
    }

    @Override
    default String boundAgent() {
      return "ops-agent";
    }

    @Override
    default void start() {}

    @Override
    default void stop() {}

    @Override
    default ChannelStatus status() {
      return ChannelStatus.ok("ops-wa", "whatsapp", "ops-agent", ChannelStatus.State.CONNECTED);
    }

    @Override
    default void sendReply(String chatId, String text, String replyToMessageId) {}
  }
}
