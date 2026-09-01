package io.oryxos.web.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * GET /api/v1/audit/trace/{traceId} 视图（021）：单轮处理的合并时间线——LLM 与工具调用按发生时间排序， 每步带类型/名称/成败/耗时；LLM 步含
 * token 与成本，TOOL 步含脱敏摘要与拦截标记。未命中 found=false 空 steps（200 不报错）。
 */
public record TraceTimelineView(
    String traceId, boolean found, List<StepView> steps, SummaryView summary) {

  /** List 组件防御性拷贝（020 SpotBugs EI_EXPOSE_REP 教训：record + List 用 copyOf 保不可变）。 */
  public TraceTimelineView {
    steps = steps == null ? List.of() : List.copyOf(steps);
  }

  /** 时间线单步：type=LLM|TOOL；LLM 步 token/cost 有值，TOOL 步摘要字段有值，互斥字段为 null 不序列化歧义。 */
  public record StepView(
      int seq,
      String type,
      String name,
      boolean success,
      long durationMs,
      Instant at,
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens,
      Long costMicros,
      String inputSummary,
      String resultSummary,
      String errorMessage,
      String blockedBy) {}

  /** 汇总：totalDurationMs 为各步耗时合计（对齐契约 §3 示例口径）；costMicros 无任何已计量调用时为 null。 */
  public record SummaryView(
      int steps,
      int llmCalls,
      int toolCalls,
      long totalTokens,
      Long costMicros,
      long totalDurationMs) {}
}
