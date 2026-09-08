# Contract: Trace 串联、回传与脱敏

**Feature**: 021-audit-trace | **Date**: 2026-09-01 | 原则五：接口即承诺

## 1. Trace 语义承诺

1. **边界**：一个 trace ID = 一次消息处理（一轮 ReAct 的全部 LLM/工具调用共享，含失败与被策略/沙箱拦截的步骤）；同会话不同消息各有各的 ID。
2. **覆盖**：全部触发源（REST 会话消息 / 无状态调用 / 管理台会话与触发 / 定时任务 / 飞书入站 / CLI）。
3. **同值贯穿**：审计三表、结构化日志（MDC `traceId` 字段）、回传通道使用同一个值——不是多套 ID。
4. **并发隔离**：并发处理互不串号（同步模型 + 线程隔离）。
5. **旧数据兼容**：升级前记录 trace 为空，既有查询不受影响；按 trace 查询如实返回空。
6. **形态**：UUID 字符串，对外形态固化不再变更。

## 2. 回传通道

| 通道 | 形态 |
|------|------|
| REST 非流式 | `MessageResponse` 增 `traceId` 字段：`{"reply":"…","traceId":"<uuid>"}`（既有字段不变） |
| SSE 流式 | 流建立后先发 `event: trace` / `data: {"traceId":"<uuid>"}`（新事件类型，旧客户端忽略）；`done` 负载同带 `traceId` |
| 执行历史 | `AgentExecutionView` 增 `traceId` 字段；管理台执行历史行内可见 |

## 3. 时间线查询

**GET** `/api/v1/audit/trace/{traceId}`（受 018 认证门禁保护）

```json
// data —— TraceTimelineView
{
  "traceId": "…", "found": true,
  "steps": [
    { "seq": 1, "type": "LLM",  "name": "glm-4-flash", "success": true, "durationMs": 1200,
      "at": "…", "promptTokens": 812, "completionTokens": 64, "totalTokens": 876, "costMicros": 120 },
    { "seq": 2, "type": "TOOL", "name": "save_memory", "success": true, "durationMs": 15,
      "at": "…", "inputSummary": "{\"content\":\"…\"}", "resultSummary": "OK", "blockedBy": null },
    { "seq": 3, "type": "LLM",  "name": "glm-4-flash", "success": true, "durationMs": 900, "at": "…", "totalTokens": 540 }
  ],
  "summary": { "steps": 3, "llmCalls": 2, "toolCalls": 1,
               "totalTokens": 1416, "costMicros": 260, "totalDurationMs": 2115 }
}
```

- 步骤按发生时间排序；TOOL 步的 `inputSummary`/`resultSummary`/`errorMessage` 为**截断（200 字符）+ 脱敏**后的展示值；被 020 策略拦截的步骤 `blockedBy: "policy"` 可见。
- 未命中：`found: false`、`steps: []`（HTTP 200，不报错）。
- 既有列表 `GET /api/v1/audit/llm|tool` 的行视图增 `traceId` 字段（016 列表的 trace 维度入口）。

## 4. 脱敏承诺

- 作用面：审计展示层（时间线与未来任何内容展示面）——API key 已知前缀、`Authorization` 凭证、URL 账密段、`password/secret/token/api_key` 类字段值 → `前4字符+****` 掩码。
- 落库保持原文（排障现场完整；库访问=运维特权边界）；规则内置不可配置。
- 不含敏感形态的内容原样展示（不误伤）。

## 5. 日志互查

- 处理路径日志（LLM 调用、工具执行、错误等既有日志点）自动携带 MDC `traceId`——dev 控制台格式 `[%X{traceId:-}]`、prod JSON 字段 `traceId`（logback 配置已就位，本 feature 只负责放值）。
- 凭同一 trace ID 可在审计与日志间互查；日志检索走部署方现有设施，不建查询 API。

## 6. 兼容性承诺

- 全部变更为 JSON 字段/事件类型/表列的**纯增量**；既有字段、事件、列语义零变化；审计契约（Auditor 接口）零改动。
