package io.oryxos.core.channel;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * 测试用 {@link HttpClient}：按预置响应应答，不触碰网络，并记录收到的请求。
 *
 * <p>存在的理由：几个渠道的 {@code *AccessTokenClient} 把换票端点硬编码在常量里（QQ 是 {@code https://api.bot.qq.com}），本地
 * {@code HttpServer} 拦不到；而项目纪律要求不为测试放宽生产代码的构造签名。 这些客户端的构造器已经接受一个 {@link HttpClient}，所以一个假的 {@code
 * HttpClient} 是唯一的注入点。
 *
 * <p><b>只实现同步 {@code send} 与 {@link HttpResponse.BodyHandlers#ofString()}</b>：预置响应本身就是字符串。 异步方法一律抛
 * {@link UnsupportedOperationException}；换用别的 {@code BodyHandler} 取正文会在使用处抛 {@link
 * ClassCastException}——本桩不模拟其它正文类型。
 */
public final class StubHttpClient extends HttpClient {

  private final Function<HttpRequest, HttpResponse<String>> responder;
  private final List<HttpRequest> requests = new CopyOnWriteArrayList<>();

  private StubHttpClient(Function<HttpRequest, HttpResponse<String>> responder) {
    this.responder = responder;
  }

  /** 对每个请求都返回同一个状态码与正文。 */
  public static StubHttpClient always(int statusCode, String body) {
    return new StubHttpClient(request -> response(statusCode, body, request));
  }

  /** 按请求自己决定响应（需要区分同一客户端的多次调用时用）。 */
  public static StubHttpClient responding(Function<HttpRequest, HttpResponse<String>> responder) {
    return new StubHttpClient(responder);
  }

  /** 构造一个不依赖真实连接的响应。 */
  public static HttpResponse<String> response(int statusCode, String body, HttpRequest request) {
    return new StubResponse(statusCode, body, request);
  }

  /** 已收到的请求，按先后顺序。 */
  public List<HttpRequest> requests() {
    return List.copyOf(requests);
  }

  /** 已收到的请求数（断言「缓存命中时不发第二次」用）。 */
  public int requestCount() {
    return requests.size();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    requests.add(request);
    return (HttpResponse<T>) responder.apply(request);
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    throw new UnsupportedOperationException("StubHttpClient 只实现同步 send");
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> handler,
      HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
    throw new UnsupportedOperationException("StubHttpClient 只实现同步 send");
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return Optional.empty();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return Optional.empty();
  }

  @Override
  public Redirect followRedirects() {
    return Redirect.NEVER;
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return Optional.empty();
  }

  @Override
  public SSLContext sslContext() {
    try {
      return SSLContext.getDefault();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("无法取得默认 SSLContext", e);
    }
  }

  @Override
  public SSLParameters sslParameters() {
    return new SSLParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return Optional.empty();
  }

  @Override
  public Version version() {
    return Version.HTTP_1_1;
  }

  @Override
  public Optional<Executor> executor() {
    return Optional.empty();
  }

  private static final class StubResponse implements HttpResponse<String> {

    private final int statusCode;
    private final String body;
    private final HttpRequest request;

    private StubResponse(int statusCode, String body, HttpRequest request) {
      this.statusCode = statusCode;
      this.body = body;
      this.request = request;
    }

    @Override
    public int statusCode() {
      return statusCode;
    }

    @Override
    public HttpRequest request() {
      return request;
    }

    @Override
    public Optional<HttpResponse<String>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(Map.of(), (name, value) -> true);
    }

    @Override
    public String body() {
      return body;
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return request.uri();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }
  }
}
