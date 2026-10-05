package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.channel.telegram.TelegramChannelAdapter;
import io.oryxos.core.channel.ChannelAdminService;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelConfigLoader;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundChannelRegistry;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.FileSandboxProperties;
import io.oryxos.tool.sandbox.HttpSandboxProperties;
import io.oryxos.tool.sandbox.SandboxAction;
import io.oryxos.tool.sandbox.ShellSandboxProperties;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 上线失败时不得把凭证写进诊断文案：Telegram 把 bot token 放在出站 URL 的路径里，令牌含空白时 URL 无法解析， 而沙箱守卫是唯一的捕获点——它的「非法
 * URL」文案会回带整条 URL。
 *
 * <p>这条文案的落点是 {@code ChannelStatus.error} → {@code GET /api/v1/channels/status}（core-stage 默认无鉴权） 与
 * {@code LOG.error}。整条链路用真的 {@link WhitelistSandbox} 跑：渠道 YAML → ChannelAdminService →
 * TelegramChannelAdapter.start → OutboundGuard → 沙箱。
 */
class ChannelStartupCredentialLeakTest {

  /** 令牌本体：断言只关心它有没有出现在文案里。 */
  private static final String TOKEN = "7712345678:AAH-real-bot-token";

  @TempDir Path tempDir;

  @Test
  @DisplayName("令牌含空白（粘贴事故）时，上线失败的文案不得回带令牌")
  void startupFailureDoesNotEchoTheToken() throws Exception {
    ChannelAdminService admin = adminsWithTelegramChannel(TOKEN + " ");

    admin.startAll();

    ChannelStatus status = onlyStatus(admin);
    assertEquals(ChannelStatus.State.ERROR, status.state());
    assertFalse(
        status.error().contains(TOKEN),
        "上线失败的文案里出现了 bot token（会经 GET /api/v1/channels/status 外泄）: " + status.error());
    assertTrue(status.error().contains("app_id"), "文案应当点名出问题的字段，否则运维无从下手: " + status.error());
  }

  @Test
  @DisplayName("形状正常的令牌不会被这道检查误拒：仍然走到域名白名单那一关")
  void aUsableTokenStillReachesTheWhitelistCheck() throws Exception {
    ChannelAdminService admin = adminsWithTelegramChannel(TOKEN);

    admin.startAll();

    ChannelStatus status = onlyStatus(admin);
    assertEquals(ChannelStatus.State.ERROR, status.state());
    assertTrue(status.error().contains("白名单"), "形状正常的令牌应当走到域名白名单这一关，而不是被前置检查拒掉: " + status.error());
    assertFalse(status.error().contains(TOKEN), "域名不在白名单这条文案只打 host，本来就不该带令牌: " + status.error());
  }

  private ChannelStatus onlyStatus(ChannelAdminService admin) {
    List<ChannelStatus> statuses = admin.status();
    assertEquals(1, statuses.size(), "应当只有一个渠道的登记");
    return statuses.get(0);
  }

  /**
   * 一份 channels.yaml + 真的 ChannelAdminService，守卫接到真的 WhitelistSandbox。
   *
   * <p>域名白名单故意留空（deny-all）：这样两条用例都在出网之前就被挡下，测试不会真的连 api.telegram.org —— 而「令牌含空白」那条在到达白名单判断之前就已经死在
   * URL 解析上了。
   */
  private ChannelAdminService adminsWithTelegramChannel(String appId) throws Exception {
    Path configFile = tempDir.resolve("channels.yaml");
    Files.writeString(
        configFile,
        """
        channels:
          - name: ops-tg
            type: telegram
            app_id: "%s"
            app_secret: ops_bot
            agent: ops-agent
        """
            .formatted(appId));

    WhitelistSandbox sandbox =
        new WhitelistSandbox(
            new FileSandboxProperties(List.of()),
            new ShellSandboxProperties(List.of()),
            new HttpSandboxProperties(List.of()));
    OutboundGuard guard = url -> sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url));

    ProfileRegistry profiles = mock(ProfileRegistry.class);
    when(profiles.get("ops-agent")).thenReturn(Optional.of(mock(Profile.class)));
    Map<String, Function<ChannelConfig, InboundChannelAdapter>> factories =
        Map.of(
            TelegramChannelAdapter.TYPE,
            config ->
                new TelegramChannelAdapter(
                    config, profiles, mock(InboundMessageService.class), guard));

    return new ChannelAdminService(
        new ChannelConfigLoader(configFile), new InboundChannelRegistry(), profiles, factories);
  }
}
