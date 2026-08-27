package io.oryxos.core.agent;

/**
 * 一次消息处理过程的流式观察者（019-sse-streaming，契约见 specs/019 data-model.md）。
 *
 * <p>三个回调全部在处理线程上<b>同步</b>执行（宪法 VII：不引入异步编程模型）——实现方的写出阻塞即是天然背压。 消费面各自实现：Web 的 SSE 写出器、CLI
 * 的终端打印器；核心循环只认本接口（依赖倒置）。
 *
 * <p>不含 onDone/onError：终结语义由调用方掌握——{@code AgentService} 正常返回即完成、抛异常即失败， 无需在 listener 里重复表达。
 */
public interface StreamListener {

  /** 全空实现：非流式路径统一走它，保证单一代码路径（ReActLoop 据此判定是否走 provider 流式）。 */
  StreamListener NOOP = new StreamListener() {};

  /** Provider 流出一段回复文本增量。 */
  default void onToken(String delta) {}

  /** ReActLoop 即将执行一个工具调用。 */
  default void onToolStart(String toolName) {}

  /** 工具调用返回。 */
  default void onToolEnd(String toolName, boolean success) {}
}
