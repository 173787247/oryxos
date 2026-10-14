package io.oryxos.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.oryxos.core.channel.ChannelConfig;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 钉住 {@code extra.poll_timeout_seconds} 的解析判据。
 *
 * <p>这个键是为**受限网络**加的：企业代理/隧道常把超过 ~20 秒的空闲连接切断，长轮询于是永远拿不到消息 （表现为 {@code EOF reached while
 * reading}，而不是超时）。可调之后，同一个 25 秒的缺省在直连环境里 仍然省请求，而受限环境能降到代理的容忍窗口内。
 *
 * <p>判据的重点是**这个可选键绝不能把渠道弄挂**：任何畸形值都回落到缺省并告警，而不是抛异常。
 */
class TelegramPollTimeoutTest {

  private static ChannelConfig config(Map<String, String> extra) {
    return new ChannelConfig("tg", "telegram", "app-id", "app-secret", "agent", true, extra, null);
  }

  @Test
  @DisplayName("未配置：用 25 秒缺省（直连环境的常规用法）")
  void defaultsWhenAbsent() {
    assertEquals(
        TelegramChannelAdapter.DEFAULT_POLL_TIMEOUT_SECONDS,
        TelegramChannelAdapter.resolvePollTimeoutSeconds(config(Map.of())));
  }

  @Test
  @DisplayName("配置 10：受限网络下的取值被采纳")
  void acceptsConfiguredValue() {
    assertEquals(
        10,
        TelegramChannelAdapter.resolvePollTimeoutSeconds(
            config(Map.of(TelegramChannelAdapter.EXTRA_POLL_TIMEOUT, "10"))));
  }

  @Test
  @DisplayName("边界值 1 与 50 都接受（50 是 Telegram 的上限）")
  void acceptsBoundaries() {
    assertEquals(
        TelegramChannelAdapter.MIN_POLL_TIMEOUT_SECONDS,
        TelegramChannelAdapter.resolvePollTimeoutSeconds(
            config(Map.of(TelegramChannelAdapter.EXTRA_POLL_TIMEOUT, "1"))));
    assertEquals(
        TelegramChannelAdapter.MAX_POLL_TIMEOUT_SECONDS,
        TelegramChannelAdapter.resolvePollTimeoutSeconds(
            config(Map.of(TelegramChannelAdapter.EXTRA_POLL_TIMEOUT, "50"))));
  }

  @Test
  @DisplayName("非数字：回落缺省，而不是让渠道起不来")
  void fallsBackOnNonNumeric() {
    assertEquals(
        TelegramChannelAdapter.DEFAULT_POLL_TIMEOUT_SECONDS,
        TelegramChannelAdapter.resolvePollTimeoutSeconds(
            config(Map.of(TelegramChannelAdapter.EXTRA_POLL_TIMEOUT, "abc"))));
  }

  @Test
  @DisplayName("越界（0 与 99）：回落缺省")
  void fallsBackOnOutOfRange() {
    for (String bad : new String[] {"0", "-1", "51", "99", "99999"}) {
      assertEquals(
          TelegramChannelAdapter.DEFAULT_POLL_TIMEOUT_SECONDS,
          TelegramChannelAdapter.resolvePollTimeoutSeconds(
              config(Map.of(TelegramChannelAdapter.EXTRA_POLL_TIMEOUT, bad))),
          "值 " + bad + " 应回落缺省");
    }
  }

  @Test
  @DisplayName("空白与带空格的数字：前者回落，后者被接受")
  void handlesWhitespace() {
    assertEquals(
        TelegramChannelAdapter.DEFAULT_POLL_TIMEOUT_SECONDS,
        TelegramChannelAdapter.resolvePollTimeoutSeconds(
            config(Map.of(TelegramChannelAdapter.EXTRA_POLL_TIMEOUT, "   "))));
    assertEquals(
        15,
        TelegramChannelAdapter.resolvePollTimeoutSeconds(
            config(Map.of(TelegramChannelAdapter.EXTRA_POLL_TIMEOUT, "  15  "))));
  }

  @Test
  @DisplayName("HTTP 超时留有余量：必须宽于长轮询本身，否则每轮都自杀")
  void httpTimeoutExceedsPollTimeout() {
    assertEquals(
        10, TelegramChannelAdapter.HTTP_TIMEOUT_MARGIN_SECONDS, "余量太小会让 25 秒的长轮询撞上 HTTP 超时");
    org.junit.jupiter.api.Assertions.assertTrue(
        TelegramChannelAdapter.MAX_POLL_TIMEOUT_SECONDS
                + TelegramChannelAdapter.HTTP_TIMEOUT_MARGIN_SECONDS
            <= 60,
        "最坏情况下（50 秒长轮询）HTTP 超时不该离谱地长");
  }
}
