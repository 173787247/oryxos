package io.oryxos.channel.dingtalk;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DingTalkProgressStreamTest {

  private DingTalkMessageSender sender;
  private DingTalkProgressStream stream;

  @BeforeEach
  void setUp() {
    sender = mock(DingTalkMessageSender.class);
    stream = new DingTalkProgressStream(sender, "cid-1", "uid-1");
  }

  @Test
  @DisplayName("start 发思考占位；finish 再发终态")
  void startAndFinish() {
    stream.start();
    stream.onToken("x");
    stream.finish("done");

    verify(sender).send(eq("cid-1"), eq(DingTalkProgressStream.THINKING_REPLY), eq("uid-1"));
    verify(sender).send(eq("cid-1"), eq("done"), eq("uid-1"));
    verify(sender, times(2))
        .send(eq("cid-1"), org.mockito.ArgumentMatchers.anyString(), eq("uid-1"));
  }

  @Test
  @DisplayName("fail 默认文案且幂等")
  void failDefault() {
    stream.start();
    stream.fail(" ");
    stream.fail("x");

    verify(sender).send(eq("cid-1"), eq(DingTalkProgressStream.THINKING_REPLY), eq("uid-1"));
    verify(sender).send(eq("cid-1"), eq(DingTalkProgressStream.FAILED_REPLY), eq("uid-1"));
    verify(sender, times(2))
        .send(eq("cid-1"), org.mockito.ArgumentMatchers.anyString(), eq("uid-1"));
  }
}
