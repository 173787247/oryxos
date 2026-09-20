package io.oryxos.core.durable;

/** 恢复执行时的一次性审批放行（043 / #465）：resume 路径在执行待批工具前置入，ToolExecutor 命中则跳过审批闸。 */
public final class ApprovalGrantContext {

  private static final ThreadLocal<Grant> CURRENT = new ThreadLocal<>();

  private ApprovalGrantContext() {}

  public record Grant(String checkpointId, String toolName, String toolCallId) {}

  public static Scope open(Grant grant) {
    CURRENT.set(grant);
    return new Scope();
  }

  public static Grant current() {
    return CURRENT.get();
  }

  public static boolean grants(String toolName, String toolCallId) {
    Grant g = CURRENT.get();
    if (g == null) {
      return false;
    }
    if (toolName != null && !toolName.equals(g.toolName())) {
      return false;
    }
    if (toolCallId != null && g.toolCallId() != null && !toolCallId.equals(g.toolCallId())) {
      return false;
    }
    return true;
  }

  public static final class Scope implements AutoCloseable {
    @Override
    public void close() {
      CURRENT.remove();
    }
  }
}
