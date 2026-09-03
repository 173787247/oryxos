package io.oryxos.channel.wecom;

import io.oryxos.core.channel.InboundProgressStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 企微进度流：平台无消息 PATCH，采用「占位 markdown + 可选一次工具行 + 终态再发」（对齐飞书 {@code InboundProgressStream} 契约，但不逐
 * token 刷屏）。
 *
 * <p>{@link #start()} 成功后编排走流式路径并跳过延迟「处理中」文本，避免双提示。
 */
final class WeComProgressStream implements InboundProgressStream {

  private static final Logger LOG = LoggerFactory.getLogger(WeComProgressStream.class);

  static final String THINKING_REPLY = "⏳ 正在思考…";
  static final String FAILED_REPLY = "抱歉，这次处理失败了，请稍后重试或联系管理员。";

  private final WeComMessageSender sender;
  private final String chatId;
  private final String replyToMessageId;
  private boolean finished;
  private boolean toolNotified;

  WeComProgressStream(WeComMessageSender sender, String chatId, String replyToMessageId) {
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
    // 企微无原地更新；忽略增量，终态一次性发出
  }

  @Override
  public void onToolStart(String toolName) {
    if (finished || toolNotified) {
      return;
    }
    toolNotified = true;
    sender.send(chatId, "🔧 正在执行 `" + safeName(toolName) + "` …", replyToMessageId);
  }

  @Override
  public void onToolEnd(String toolName, boolean success) {
    // no-op：避免多条刷屏；终态一次发出
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
      LOG.warn("企微进度流失败态发送失败: {}", sanitize(e.getMessage()));
      throw e;
    }
  }

  private static String safeName(String toolName) {
    if (toolName == null || toolName.isBlank()) {
      return "tool";
    }
    return toolName.replace('`', '\'').strip();
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
