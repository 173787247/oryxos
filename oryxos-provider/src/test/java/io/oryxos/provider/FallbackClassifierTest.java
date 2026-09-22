package io.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** 023 US1：可切换性分类表逐行钉死（R3）。 */
class FallbackClassifierTest {

  private static RestClientResponseException status(int code) {
    return new RestClientResponseException(
        "status " + code, code, "st", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
  }

  private static OpenAIServiceException openaiStatus(int code) {
    OpenAIServiceException ex = mock(OpenAIServiceException.class);
    when(ex.statusCode()).thenReturn(code);
    return ex;
  }

  @Test
  void 服务端与限流认证类_可切换() {
    assertThat(FallbackClassifier.isSwitchable(status(500))).isTrue();
    assertThat(FallbackClassifier.isSwitchable(status(502))).isTrue();
    assertThat(FallbackClassifier.isSwitchable(status(503))).isTrue();
    assertThat(FallbackClassifier.isSwitchable(status(429))).isTrue(); // 限流=换配额
    assertThat(FallbackClassifier.isSwitchable(status(401))).isTrue(); // 本家凭证问题=换家
    assertThat(FallbackClassifier.isSwitchable(status(403))).isTrue();
    assertThat(FallbackClassifier.isSwitchable(status(408))).isTrue();
  }

  @Test
  void 业务性失败_不切换() {
    assertThat(FallbackClassifier.isSwitchable(status(400))).isFalse(); // 请求非法换家无意义
    assertThat(FallbackClassifier.isSwitchable(status(404))).isFalse();
    assertThat(FallbackClassifier.isSwitchable(status(422))).isFalse();
  }

  @Test
  void OpenAI_SDK异常_按状态码判定() {
    assertThat(FallbackClassifier.isSwitchable(openaiStatus(400))).isFalse();
    assertThat(FallbackClassifier.isSwitchable(openaiStatus(401))).isTrue();
    assertThat(FallbackClassifier.isSwitchable(openaiStatus(429))).isTrue();
    assertThat(FallbackClassifier.isSwitchable(openaiStatus(502))).isTrue();
  }

  @Test
  void 网络与超时类_可切换() {
    assertThat(FallbackClassifier.isSwitchable(new ResourceAccessException("connect refused")))
        .isTrue();
    assertThat(FallbackClassifier.isSwitchable(new OpenAIIoException("transport down"))).isTrue();
    assertThat(
            FallbackClassifier.isSwitchable(
                new RuntimeException("wrapped", new TimeoutException("read timeout"))))
        .isTrue();
  }

  @Test
  void 异常链深埋_逐层提取() {
    RuntimeException deep400 =
        new RuntimeException("outer", new IllegalStateException(status(400)));
    assertThat(FallbackClassifier.isSwitchable(deep400)).isFalse();
    RuntimeException deepIo =
        new RuntimeException("outer", new RuntimeException(new IOException("broken pipe")));
    assertThat(FallbackClassifier.isSwitchable(deepIo)).isTrue();
  }

  @Test
  void 无状态码的未知异常_宁多试一次() {
    assertThat(FallbackClassifier.isSwitchable(new IllegalStateException("who knows"))).isTrue();
  }
}
