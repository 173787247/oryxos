package io.oryxos.core.agent;

/**
 * tool_invocations 审计写入口（宪法 V：Day One 落库）——一次工具调用不管成没成，事后都得能查到。
 *
 * <p>实现方写入失败必须抛出异常（不得静默吞掉）。调用方必须尝试落库；落库失败时以 ERROR 日志告警，不得用审计异常 掩盖工具的真实执行结果（工具副作用已发生，成败结果都照常返回给循环）。
 */
public interface ToolInvocationAuditor {

  void record(
      String sessionId,
      String profileName,
      String toolName,
      String inputJson,
      String resultJson,
      boolean success,
      String errorMessage,
      long durationMs);

  /**
   * 带拦截来源标记的写入（020）：{@code blockedBy} 标记调用被哪道闸拦下（当前仅 {@code "policy"}=工具策略拒绝），
   * 供审计按类筛选（FR-006）；未被拦截的调用传 null。默认实现丢弃标记委托旧签名（旧实现/测试桩零破坏）—— 要落列的实现方（JPA）须覆写本方法。
   */
  default void record(
      String sessionId,
      String profileName,
      String toolName,
      String inputJson,
      String resultJson,
      boolean success,
      String errorMessage,
      String blockedBy,
      long durationMs) {
    record(
        sessionId, profileName, toolName, inputJson, resultJson, success, errorMessage, durationMs);
  }

  /**
   * 带执行后端标识的写入（024）：{@code executionBackend} 记录执行位置（"local"/"docker"）， {@code containerId} 记录
   * docker 档容器 ID（local 档 null）——供审计按后端筛选与容器溯源（FR-008）。
   * 默认实现丢弃后端信息委托上一级签名（旧实现/测试桩零破坏）；要落列的实现方（JPA）须覆写本方法。
   */
  default void record(
      String sessionId,
      String profileName,
      String toolName,
      String inputJson,
      String resultJson,
      boolean success,
      String errorMessage,
      String blockedBy,
      String executionBackend,
      String containerId,
      long durationMs) {
    record(
        sessionId,
        profileName,
        toolName,
        inputJson,
        resultJson,
        success,
        errorMessage,
        blockedBy,
        durationMs);
  }
}
