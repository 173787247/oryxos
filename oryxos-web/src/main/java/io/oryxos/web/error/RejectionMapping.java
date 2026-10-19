package io.oryxos.web.error;

import io.oryxos.core.cost.BudgetExceededException;
import io.oryxos.core.routing.ResidencyConstraintException;
import io.oryxos.provider.ProviderNotFoundException;
import org.springframework.http.HttpStatus;

/**
 * 确定性拒绝的统一映射：异常 → (状态码, 对外 message)，HTTP 边界（{@code GlobalExceptionHandler}）与 SSE in-band
 * 错误事件（{@code SseStreamSupport}）共用这一份。
 *
 * <p>为什么必须共用：SSE 的响应头在 action 执行之前就已提交（{@code SseWriter} 构造时 setContentType + flush），流内异常到不了
 * {@code @ExceptionHandler}，只能写成 {@code event: error} 的 in-band 事件。两处各写一份 映射的话，加第四类拒绝时必然漂。
 */
public final class RejectionMapping {

  private RejectionMapping() {}

  /**
   * @return 映射结果；{@code null} 表示不是已知的确定性拒绝——调用方仍按 500 处理（catch-all / 不透明文案）
   */
  public static Mapped map(RuntimeException ex) {
    if (ex instanceof BudgetExceededException) {
      // 402：额度/付费语义。429 在本应用固定表示同会话排队超限（限流），不复用。
      return new Mapped(HttpStatus.PAYMENT_REQUIRED.value(), ex.getMessage());
    }
    if (ex instanceof ProviderNotFoundException) {
      // 400：本质是服务端配置错误（AGENT.md 点了未注册的 provider），但它是确定性的——客户端重试不会成功，
      // 消息点名要修的 provider。它不是路径资源，所以不用 404。
      return new Mapped(HttpStatus.BAD_REQUEST.value(), ex.getMessage());
    }
    if (ex instanceof ResidencyConstraintException) {
      // 403：策略拒绝，与资产治理/审批拒绝同档。
      return new Mapped(HttpStatus.FORBIDDEN.value(), ex.getMessage());
    }
    return null;
  }

  /** HTTP 状态码 + 对外 message（只来自异常自身，不含堆栈/内部细节）。 */
  public record Mapped(int status, String message) {}
}
