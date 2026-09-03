package io.oryxos.channel.dingtalk;

import io.oryxos.core.channel.InboundProgressStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 钉钉进度流：sessionWebhook 出站无原地编辑，采用「占位 markdown + 终态再发」两跳（对称企微 {@code WeComProgressStream}）。 */
final class DingTalkProgressStream implements InboundProgressStream {

  private static final Logger LOG = LoggerFactory.getLogger(DingTalkProgressStream.class);

  static final String THINKING_REPLY = "⏳ 正在思考…";
  static final String FAILED_REPLY = "抱歉，这次处理失败了，请稍后重试或联系管理员。";

  private final DingTalkMessageSender sender;
  private final String chatId;
  private final String replyToMessageId;
  private boolean finished;

  DingTalkProgressStream(DingTalkMessageSender sender, String chatId, String replyToMessageId) {
    this.sender = sender;
    this.chatId = chatId;
    this.replyToMessageId = replyToMessageId;
  }

  @Override
  public void start() {
    sender.send(chatId, THINKING_REPLY, replyToMessageId);
  }

  @Override
  public void onToken(String delta) {
    // 钉钉无原地更新；忽略增量
  }

  @Override
  public void onToolStart(String toolName) {
    // no-op
  }

  @Override
  public void onToolEnd(String toolName, boolean success) {
    // no-op
  }

  @Override
  public void finish(String finalText) {
    if (finished) {
      return;
    }
    finished = true;
    String body = finalText == null || finalText.isBlank() ? "（空回复）" : finalText;
    sender.send(chatId, body, replyToMessageId);
  }

  @Override
  public void fail(String errorMessage) {
    if (finished) {
      return;
    }
    finished = true;
    String body =
        errorMessage == null || errorMessage.isBlank() ? FAILED_REPLY : errorMessage.strip();
    try {
      sender.send(chatId, body, replyToMessageId);
    } catch (RuntimeException e) {
      LOG.warn("钉钉进度流失败态发送失败: {}", sanitize(e.getMessage()));
      throw e;
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
