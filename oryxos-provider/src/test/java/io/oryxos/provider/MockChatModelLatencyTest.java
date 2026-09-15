package io.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** 039 US4：mock 时延——默认 0 零变化；配置后生效（吞吐压测用）。 */
class MockChatModelLatencyTest {

  @AfterEach
  void cleanup() {
    System.clearProperty("oryxos.mock.latency-ms");
  }

  @Test
  @DisplayName("默认零时延：快速返回（现状零变化）")
  void defaultLatencyIsZero() {
    long begin = System.nanoTime();
    new MockChatModel().call(new Prompt(List.of(new UserMessage("hi"))));
    assertThat((System.nanoTime() - begin) / 1_000_000).isLessThan(200);
  }

  @Test
  @DisplayName("-Doryxos.mock.latency-ms=300 生效")
  void configuredLatencyApplies() {
    System.setProperty("oryxos.mock.latency-ms", "300");
    long begin = System.nanoTime();
    new MockChatModel().call(new Prompt(List.of(new UserMessage("hi"))));
    assertThat((System.nanoTime() - begin) / 1_000_000).isGreaterThanOrEqualTo(280);
  }
}
