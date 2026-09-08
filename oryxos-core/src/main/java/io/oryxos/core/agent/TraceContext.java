package io.oryxos.core.agent;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * "当前这轮处理的 trace ID"线程上下文（021）：一次消息处理 = 一个 trace ID，贯穿审计三表、 结构化日志（MDC {@code traceId}）与回传通道。
 *
 * <p>镜像 {@link ProfileContext} 纪律：入口 open、出口 finally close。虚拟线程每请求独立天然不串； 但复用平台线程的场景下不清就会串号，所以
 * {@link Scope#close()} 必达（try-with-resources 钉死）。 与 ProfileContext 的差异：这里用 owner 语义支持嵌套——REST/SSE
 * controller 先 open 拿 ID 入响应， AgentService 兜底 openIfAbsent 复用同一 ID（owner=false，close 不清外层）。
 *
 * <p>MDC 与 ThreadLocal 同步置入/清理：logback 已预埋 {@code %X{traceId:-}}（dev）与 JSON 字段（prod），
 * 本类放值即生效，日志配置零改动。
 */
public final class TraceContext {

  /** MDC 键名，与 logback-spring.xml 预埋的 {@code traceId} 占位对齐。 */
  public static final String MDC_KEY = "traceId";

  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

  private TraceContext() {}

  /**
   * 无当前 trace 则生成 UUID 并置入 ThreadLocal + MDC（owner=true）；已有则复用现值（owner=false）。 返回的 Scope 必须
   * try-with-resources / finally 关闭。
   */
  public static Scope openIfAbsent() {
    String existing = CURRENT.get();
    if (existing != null) {
      return new Scope(existing, false);
    }
    return install(UUID.randomUUID().toString());
  }

  /**
   * 用外部生成的 trace ID 置入当前线程（owner=true）——仅限跨线程显式传递场景（triggerAsync 主线程生成 → 后台虚拟线程置入，R4
   * 唯一跨线程点）。调用方保证目标线程无既有 trace。
   */
  public static Scope open(String traceId) {
    return install(traceId);
  }

  /** 当前 trace ID，未开启时为 null。 */
  public static String current() {
    return CURRENT.get();
  }

  private static Scope install(String traceId) {
    CURRENT.set(traceId);
    MDC.put(MDC_KEY, traceId);
    return new Scope(traceId, true);
  }

  /** trace 作用域：仅 owner 关闭时清 ThreadLocal（remove 防泄漏）与 MDC；嵌套复用者 close 为空操作。 */
  public static final class Scope implements AutoCloseable {

    private final String traceId;
    private final boolean owner;

    private Scope(String traceId, boolean owner) {
      this.traceId = traceId;
      this.owner = owner;
    }

    public String traceId() {
      return traceId;
    }

    public boolean isOwner() {
      return owner;
    }

    @Override
    public void close() {
      if (owner) {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
      }
    }
  }
}
