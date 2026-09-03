package io.oryxos.core.agent;

/**
 * 当前工具调用的执行上下文——按线程隔离携带「这一步是替哪个 Agent 跑的」（第 30 节：Agent 专属记忆）。
 *
 * <p>为什么用 ThreadLocal：{@link io.oryxos.core.OryxTool#execute} 的签名只有 JsonNode 入参，没有执行上下文；而
 * save_memory / recall_memory 要落到「当前 Agent 自己的」MEMORY.md，就必须知道 Agent 名。核心阶段是同步阻塞模型（宪法七，无
 * Reactor/异步），一次 ReAct 循环整体跑在一条（虚拟）线程上，所以 {@link ToolExecutor} 在执行工具前置入、执行后清除，天然按 Agent 隔离—— 不必改动
 * OryxTool 接口，也不牵动任何非记忆工具。
 *
 * <p>若将来引入流式 / 异步（扩展阶段），此处的线程绑定语义需重新审视（配合上下文透传）。
 */
public final class ToolExecutionContext {

  private static final ThreadLocal<String> AGENT = new ThreadLocal<>();

  /**
   * 容器 ID 惰性读取器（024）：docker 档审计时才回读 cidfile——start 时容器 ID 由 docker 异步写入， 审计发生在进程结束后，届时必然可读。local 档
   * / 无上下文为 null。
   */
  private static final ThreadLocal<java.util.function.Supplier<String>> CONTAINER_ID =
      new ThreadLocal<>();

  /** 执行后端标识（024）："docker" / null（null ≡ local，见 ToolInvocation.executionBackend）。 */
  private static final ThreadLocal<String> EXECUTION_BACKEND = new ThreadLocal<>();

  private ToolExecutionContext() {}

  /** 置入当前 Agent 名（ToolExecutor 执行工具前调用；读记忆的门面在 buildContext/readAll 前后也临时置入）。 */
  public static void setAgentName(String name) {
    AGENT.set(name);
  }

  /** 当前 Agent 名，可能为 {@code null}（无上下文，如非 Agent 触发的直接调用）。 */
  public static String agentName() {
    return AGENT.get();
  }

  /**
   * 置入执行后端（024，DockerProcessStarter 启动时调用）：backend 为 "docker" 时同时携带容器 ID 惰性读取器； local 档不置入（保持 null
   * ≡ local 语义，审计缺省归一 local 见 JpaToolInvocationAuditor）。
   */
  public static void setExecution(String backend, java.util.function.Supplier<String> containerId) {
    EXECUTION_BACKEND.set(backend);
    CONTAINER_ID.set(containerId);
  }

  /** 当前执行后端，可能为 {@code null}（local / 未置入）。 */
  public static String executionBackend() {
    return EXECUTION_BACKEND.get();
  }

  /** 当前容器 ID（惰性读取），local 档 / 未就绪为 {@code null}。 */
  public static String containerId() {
    java.util.function.Supplier<String> supplier = CONTAINER_ID.get();
    return supplier == null ? null : supplier.get();
  }

  /** 清除（执行工具后调用，避免线程复用时串到下一个 Agent）。 */
  public static void clear() {
    AGENT.remove();
    EXECUTION_BACKEND.remove();
    CONTAINER_ID.remove();
  }
}
