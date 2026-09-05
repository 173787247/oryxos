package io.oryxos.core.agent;

/**
 * 当前轮的 agent_executions 主键传递（026 T018，与 ProfileContext 同形态）：triggerAsync/调度路径在 start 后 set、finally
 * clear；AgentService 认领租约后读它把 executionId 关联进租约行——副本崩溃时 接管方能给悬空轮补失败留痕。无 execution 的路径（Web
 * 同步/SSE/CLI）不 set，留痕降级为回收日志+指标。
 */
public final class ExecutionContext {

  private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

  private ExecutionContext() {}

  public static void set(long executionId) {
    CURRENT.set(executionId);
  }

  /** 当前轮 execution id；无（Web/SSE/CLI 路径）返回 null。 */
  public static Long currentId() {
    return CURRENT.get();
  }

  public static void clear() {
    CURRENT.remove();
  }
}
