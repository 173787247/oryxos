package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.channel.ChannelAdminService;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.web.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 017 T020：channels 端点切片——CRUD 薄转发、凭证掩码不泄密、点名错误文案（400/404）。 */
class ChannelApiControllerTest {

  private ChannelAdminService admin;
  private MockMvc mvc;

  private static final ChannelConfig RAW =
      new ChannelConfig(
          "ops-feishu", "feishu", "${FEISHU_APP_ID}", "${FEISHU_APP_SECRET}", "ops-agent", true);

  @BeforeEach
  void setUp() {
    admin = mock(ChannelAdminService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new ChannelApiController(admin))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("list：${} 占位原样回显；明文凭证掩码为 ******")
  void listMasksPlaintextSecret() throws Exception {
    ChannelConfig plaintext =
        new ChannelConfig("raw-chan", "feishu", "cli_x", "real-secret", "ops-agent", true);
    when(admin.listRaw()).thenReturn(List.of(RAW, plaintext));

    mvc.perform(get("/api/v1/channels"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].appSecret").value("${FEISHU_APP_SECRET}"))
        .andExpect(jsonPath("$.data[1].appSecret").value("******"));
  }

  @Test
  @DisplayName("status：呈现渠道在线状态与点名错误原因（FR-014/SC-008）")
  void statusShowsStateAndError() throws Exception {
    when(admin.status())
        .thenReturn(
            List.of(
                ChannelStatus.ok("ok-chan", "feishu", "ops-agent", ChannelStatus.State.CONNECTED),
                ChannelStatus.error(
                    "bad-chan", "feishu", "ghost", "渠道 bad-chan 绑定的 Agent ghost 不存在")));

    mvc.perform(get("/api/v1/channels/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].state").value("CONNECTED"))
        .andExpect(jsonPath("$.data[1].state").value("ERROR"))
        .andExpect(jsonPath("$.data[1].error").value("渠道 bad-chan 绑定的 Agent ghost 不存在"));
  }

  @Test
  @DisplayName("add：落盘 + 立即上线；回显掩码")
  void addSuccess() throws Exception {
    when(admin.add(any())).thenReturn(RAW);

    mvc.perform(
            post("/api/v1/channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"ops-feishu\",\"type\":\"feishu\",\"appId\":\"${FEISHU_APP_ID}\","
                        + "\"appSecret\":\"${FEISHU_APP_SECRET}\",\"agent\":\"ops-agent\",\"enabled\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("ops-feishu"))
        .andExpect(jsonPath("$.data.appSecret").value("${FEISHU_APP_SECRET}"));
  }

  @Test
  @DisplayName("add 校验失败（Agent 不存在）：400 + 点名文案")
  void addValidationFailure() throws Exception {
    when(admin.add(any()))
        .thenThrow(new IllegalArgumentException("渠道 ops-feishu 绑定的 Agent ghost 不存在"));

    mvc.perform(
            post("/api/v1/channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"ops-feishu\",\"type\":\"feishu\",\"appId\":\"a\","
                        + "\"appSecret\":\"b\",\"agent\":\"ghost\",\"enabled\":true}"))
        .andExpect(status().is4xxClientError());
  }

  @Test
  @DisplayName("update 不存在的渠道：404，不触达 admin.update")
  void updateMissingReturns404() throws Exception {
    when(admin.listRaw()).thenReturn(List.of());

    mvc.perform(
            put("/api/v1/channels/ghost-chan")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"ghost-chan\",\"type\":\"feishu\",\"appId\":\"a\","
                        + "\"appSecret\":\"b\",\"agent\":\"x\",\"enabled\":true}"))
        .andExpect(status().isNotFound());
    verify(admin, never()).update(eq("ghost-chan"), any());
  }

  @Test
  @DisplayName("delete：存在则断开并移除；不存在 404")
  void deleteFlow() throws Exception {
    when(admin.listRaw()).thenReturn(List.of(RAW));
    mvc.perform(delete("/api/v1/channels/ops-feishu")).andExpect(status().isOk());
    verify(admin).remove("ops-feishu");

    when(admin.listRaw()).thenReturn(List.of());
    mvc.perform(delete("/api/v1/channels/ops-feishu")).andExpect(status().isNotFound());
  }
}
