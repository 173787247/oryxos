package io.oryxos.core.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 无原地 PATCH 的 IM 进度流：占位 → 至多一条工具行 → 终态（企微/钉钉）。
 *
 * <p>出站由 {@link ReplyFn} 完成，渠道侧只提供 sender 绑定。
 */
public final class PlaceholderProgressStream implements InboundProgressStream {

  private static final Logger LOG = LoggerFactory.getLogger(PlaceholderProgressStream.class);

  public static final String DEFAULT_THINKING = "⏳ 正在思考…";
  public static final String DEFAULT_FAILED = "抱歉，这次处理失败了，请稍后重试或联系管理员。";

  /** 发送一条回复；失败应抛运行时异常。 */
  @FunctionalInterface
  public interface ReplyFn {
    void send(String chatId, String text, String replyToMessageId);
  }

  private final ReplyFn replyFn;
  private final String chatId;
  private final String replyToMessageId;
  private final String thinkingReply;
  private final String failedReply;
  private final String logLabel;
  private boolean finished;
  private boolean toolNotified;

  public PlaceholderProgressStream(
      ReplyFn replyFn, String chatId, String replyToMessageId, String logLabel) {
    this(replyFn, chatId, replyToMessageId, DEFAULT_THINKING, DEFAULT_FAILED, logLabel);
  }

  public PlaceholderProgressStream(
      ReplyFn replyFn,
      String chatId,
      String replyToMessageId,
      String thinkingReply,
      String failedReply,
      String logLabel) {
    this.replyFn = replyFn;
    this.chatId = chatId;
    this.replyToMessageId = replyToMessageId;
    this.thinkingReply = thinkingReply == null ? DEFAULT_THINKING : thinkingReply;
    this.failedReply = failedReply == null ? DEFAULT_FAILED : failedReply;
    this.logLabel = logLabel == null ? "IM" : logLabel;
  }

  @Override
  public void start() {
    replyFn.send(chatId, thinkingReply, replyToMessageId);
  }

  @Override
  public void onToken(String delta) {
    // 无原地更新；忽略增量
  }

  @Override
  public void onToolStart(String toolName) {
    if (finished || toolNotified) {
      return;
    }
    toolNotified = true;
    replyFn.send(chatId, "🔧 正在执行 `" + safeName(toolName) + "` …", replyToMessageId);
  }

  @Override
  public void onToolEnd(String toolName, boolean success) {
    // no-op：避免多条刷屏
  }

  @Override
  public void finish(String finalText) {
    if (finished) {
      return;
    }
    finished = true;
    String body = finalText == null || finalText.isBlank() ? "（空回复）" : finalText;
    replyFn.send(chatId, body, replyToMessageId);
  }

  @Override
  public void fail(String errorMessage) {
    if (finished) {
      return;
    }
    finished = true;
    String body =
        errorMessage == null || errorMessage.isBlank() ? failedReply : errorMessage.strip();
    try {
      replyFn.send(chatId, body, replyToMessageId);
    } catch (RuntimeException e) {
      LOG.warn("{}进度流失败态发送失败: {}", sanitize(logLabel), sanitize(e.getMessage()));
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
