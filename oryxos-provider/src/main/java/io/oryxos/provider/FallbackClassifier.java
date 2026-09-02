package io.oryxos.provider;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 可切换性分类（023，R3）：判定一次 LLM 调用失败是否值得换备用 Provider 重试。
 *
 * <p>分类原则：换一个 Provider 有合理成功预期的才算可切换——网络/超时/5xx/限流/认证（各家凭证独立）切； 400
 * 类请求本身非法不切（FR-003）。提取不到状态码的未知异常偏向切换：多试一次的代价是一次超时， 漏切的代价是本可避免的服务中断（全败仍上抛最后错误，不吞）。
 */
final class FallbackClassifier {

  /** 5xx 下界：服务端故障一律可切。 */
  private static final int SERVER_ERROR_FLOOR = 500;

  private FallbackClassifier() {}

  /** true = 值得换下一个候选重试。 */
  static boolean isSwitchable(RuntimeException e) {
    Throwable t = e;
    while (t != null) {
      // RestClient 族（provider 非流式路径）：带状态码，按码判定
      if (t instanceof RestClientResponseException rest) {
        return switchableStatus(rest.getStatusCode().value());
      }
      // WebClient 族（流式路径可能经 reactive 客户端）：同样带状态码
      if (t
          instanceof
          org.springframework.web.reactive.function.client.WebClientResponseException web) {
        return switchableStatus(web.getStatusCode().value());
      }
      // Spring AI 错误处理器的包装形态（真机验证）：4xx/5xx 被包成 (Non)TransientAiException，
      // cause 链上没有 RestClient 异常、真实状态码在 message 前缀（"400 - {json}"）——按前缀码判定
      if (t instanceof org.springframework.ai.retry.NonTransientAiException
          || t instanceof org.springframework.ai.retry.TransientAiException) {
        Integer code = leadingStatus(t.getMessage());
        if (code != null) {
          return switchableStatus(code);
        }
        // 提取不到码：信 Spring AI 的瞬时性分类——Transient 切、NonTransient 不切
        return t instanceof org.springframework.ai.retry.TransientAiException;
      }
      // 网络/超时类：连接不上、读超时、IO 断流——换端点最典型收益
      if (t instanceof ResourceAccessException
          || t instanceof IOException
          || t instanceof TimeoutException) {
        return true;
      }
      t = t.getCause();
    }
    return true; // 无状态码可依：宁多试一次备用（R3）
  }

  private static final java.util.regex.Pattern LEADING_STATUS =
      java.util.regex.Pattern.compile("^(\\d{3}) - ");

  /** 解析 Spring AI 异常 message 前缀的 HTTP 状态码；无则 null。 */
  private static Integer leadingStatus(String message) {
    if (message == null) {
      return null;
    }
    java.util.regex.Matcher matcher = LEADING_STATUS.matcher(message);
    return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
  }

  private static boolean switchableStatus(int code) {
    if (code >= SERVER_ERROR_FLOOR) {
      return true; // 服务端故障
    }
    // 429 限流=换配额；401/403 本家凭证问题=换家换凭证；408 请求超时
    return code == 429 || code == 401 || code == 403 || code == 408;
  }
}
