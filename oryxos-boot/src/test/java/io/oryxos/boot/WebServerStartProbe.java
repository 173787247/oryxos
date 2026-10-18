package io.oryxos.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时序探针（启动拒启类用例共享）：记录"Web Server 已经 start（端口已打开）"这一事实。
 *
 * <p>Spring Boot 在 {@code finishRefresh()} 里启动 Web Server 时发布 {@link
 * WebServerInitializedEvent}；{@code ApplicationRunner} 在 refresh 之后才跑。因此「启动被拒 +
 * 本探针从未触发」证明拒启发生在端口打开之前。
 *
 * <p>静态 flag + {@link #reset()}：用例串行执行；同一个 JVM 里多次启动上下文时由调用方在每次启动前显式重置。探针本身作为一个
 * {@code @Configuration} 源传给 {@code SpringApplicationBuilder}，不依赖任何生产装配。
 */
@Configuration
public class WebServerStartProbe {

  private static final AtomicBoolean STARTED = new AtomicBoolean(false);

  /** 每次启动上下文之前调用。 */
  public static void reset() {
    STARTED.set(false);
  }

  /** Web Server 是否已经 start 过（端口曾经打开）。 */
  public static boolean started() {
    return STARTED.get();
  }

  /** cause 链上的非空消息：Spring 会把启动失败包一层，真正的原因在 cause 里。 */
  public static List<String> messagesOf(Throwable failure) {
    List<String> messages = new ArrayList<>();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause.getMessage() != null) {
        messages.add(cause.getMessage());
      }
    }
    return messages;
  }

  @Bean
  ApplicationListener<WebServerInitializedEvent> webServerStartListener() {
    return new WebServerStartedListener();
  }

  /** 显式实现类而非 lambda：泛型事件类型可解析，避免收到其它 ApplicationEvent 时强转失败。 */
  static final class WebServerStartedListener
      implements ApplicationListener<WebServerInitializedEvent> {

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
      STARTED.set(true);
    }
  }
}
