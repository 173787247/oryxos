package io.oryxos.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.channel.ChannelAdminService;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelConfigLoader;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundChannelRegistry;
import io.oryxos.core.channel.StubChannelAdapter;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.web.GlobalExceptionHandler;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 017 管理台 CRUD 的「取回 → 改 → 存」路径：列表回显的是掩码，客户端把同一份 JSON 提交回来时， 掩码不得被当成新凭证落盘（否则真实凭证被静默顶掉）。 */
class ChannelMaskRoundTripTest {

  @TempDir Path tempDir;

  private ChannelConfigLoader loader;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    loader = new ChannelConfigLoader(tempDir.resolve("channels.yaml"));
    ProfileRegistry profiles = mock(ProfileRegistry.class);
    when(profiles.get("ops-agent")).thenReturn(Optional.of(mock(Profile.class)));
    Map<String, Function<ChannelConfig, InboundChannelAdapter>> factories =
        Map.of(
            "stub", c -> new StubChannelAdapter(c.name(), c.agent()),
            "telegram", c -> new StubChannelAdapter(c.name(), c.agent()));
    ChannelAdminService admin =
        new ChannelAdminService(loader, new InboundChannelRegistry(), profiles, factories);
    mvc =
        MockMvcBuilders.standaloneSetup(new ChannelApiController(admin))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("PUT 回写列表里的 ******，不得把 app_secret 顶成掩码")
  void maskedAppSecretIsNotWrittenBack() throws Exception {
    loader.save(
        List.of(new ChannelConfig("ops-tg", "stub", "app-id", "real-secret", "ops-agent", true)));

    mvc.perform(
            put("/api/v1/channels/ops-tg")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"ops-tg\",\"type\":\"stub\",\"appId\":\"app-id\","
                        + "\"appSecret\":\"******\",\"agent\":\"ops-agent\",\"enabled\":true}"))
        .andExpect(status().isOk());

    assertEquals("real-secret", loader.loadRaw().get(0).appSecret());
  }

  @Test
  @DisplayName("PUT 回写列表里的 ******，不得把 app_id 里的令牌顶成掩码")
  void maskedAppIdIsNotWrittenBack() throws Exception {
    loader.save(
        List.of(
            new ChannelConfig(
                "ops-tg", "telegram", "123456:AAH-real-bot-token", "ops_bot", "ops-agent", true)));

    mvc.perform(
            put("/api/v1/channels/ops-tg")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"ops-tg\",\"type\":\"telegram\",\"appId\":\"******\","
                        + "\"appSecret\":\"ops_bot\",\"agent\":\"ops-agent\",\"enabled\":true}"))
        .andExpect(status().isOk());

    assertEquals("123456:AAH-real-bot-token", loader.loadRaw().get(0).appId());
  }

  @Test
  @DisplayName("PUT 回写列表里的 ******，不得把 extra 凭证顶成掩码")
  void maskedExtraIsNotWrittenBack() throws Exception {
    loader.save(
        List.of(
            new ChannelConfig(
                "ops-mm",
                "stub",
                "app-id",
                "secret",
                "ops-agent",
                true,
                Map.of("access_token", "real-pat", "base_url", "https://mm.example"))));

    mvc.perform(
            put("/api/v1/channels/ops-mm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"ops-mm\",\"type\":\"stub\",\"appId\":\"app-id\","
                        + "\"appSecret\":\"secret\",\"agent\":\"ops-agent\",\"enabled\":true,"
                        + "\"extra\":{\"access_token\":\"******\","
                        + "\"base_url\":\"https://mm.example\"}}"))
        .andExpect(status().isOk());

    assertEquals("real-pat", loader.loadRaw().get(0).extra("access_token"));
  }
}
