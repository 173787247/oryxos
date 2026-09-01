# 021-audit-trace 真机验收报告

**Date**: 2026-09-01 | **环境**: WSL2 + fat JAR（`oryxos serve --port 8083`，mock provider）+ 缓存 Chromium 浏览器走查

## 走查结果（quickstart V1~V8）

| # | 场景 | 结果 | 证据 |
|---|------|------|------|
| V1 | REST 回传与全链路回放 | ✅ | invoke 响应 `data.traceId=b4fa6679…`；`GET /audit/trace/{id}` found=true，steps=[LLM, TOOL(save_memory), LLM] 时间序，summary steps=3/llmCalls=2/toolCalls=1 |
| V2 | 隔离与旧数据 | ✅ | 第二轮 `e73ca99f…` ≠ 第一轮，各查各的 3 步；无效 ID 返回 200 + found=false 空 steps；`GET /audit/llm` 行视图全部带 traceId 字段 |
| V3 | SSE 流式回传 | ✅ | 流首事件 `event: trace`（`{"traceId":"ad7c1bec…"}`），先于 tool_start/token/done；done 负载同带 traceId |
| V4 | 执行历史与触发 | ✅ | trigger 后执行记录 SUCCESS 且带 `traceId=daf2ce0d…`；按该 ID 查时间线命中后台执行全链路 [LLM, TOOL, LLM]（跨线程传递正确） |
| V5 | 日志互查 | ✅ | `grep daf2ce0d serve.log` 命中 3 行：2×「LLM 调用完成」+ 1×「工具执行完成」，均带 `[traceId]` 前缀，与时间线步骤一一对应 |
| V6 | 失败与被拦步骤入链 | ✅ | GLOBAL_DENY save_memory 后 invoke：TOOL 步 success=false、blockedBy=policy，链路仍完整 3 步；删规则恢复 |
| V7 | 脱敏双向 | ✅ | 展示层 inputSummary：`"password":"p@ss****`、`sk-a****`（原文不可见）；库中 `tool_invocations.input_json` 原文完整（`p@ss123`、`sk-abcdefgh12345678`） |
| V8 | 管理台 | ✅ | 浏览器走查（Chromium headless）：报表页 trace 查询框 → 时间线视图（步骤/类型徽标 LLM\|TOOL/成败/耗时/脱敏摘要 + 汇总行）；无效 ID 显示「未找到」；LLM 明细行 traceId 可点击回填查询并渲染时间线；截图 v8-report.png 留档 |

## SC 达成对照

| SC | 判定 | 依据 |
|----|------|------|
| SC-001 全触发源生成唯一 trace | ✅ | REST invoke（V1）/ 会话 SSE（V3）/ 手动触发后台线程（V4）各自成链；CLI/定时/飞书经 AgentService 兜底收口（架构同路径，TraceE2ETest 钉死） |
| SC-002 串联完整性 | ✅ | V1 三步同链时间序；E2E `单轮全链路_审计同traceId_时间线可回放` |
| SC-003 并发隔离 | ✅ | E2E `并发两Agent同时invoke_审计无串号`（虚拟线程并发分组断言）+ TraceContextTest 多线程用例 |
| SC-004 回传三通道 | ✅ | V1/V3/V4 + E2E Order 4~6 |
| SC-005 排障路径缩短 | ✅ | 报障→ID→时间线一步直达（V8 全流程：拿 ID → 报表页粘贴 → 完整链路与成本可见，v0.3 Demo 后半段） |
| SC-006 脱敏不误伤 | ✅ | V7 双向 + RedactorTest 8 例（四形态正例 + 中文/普通 JSON/URL 不误伤 + 转义 JSON 形态） |
| SC-007 日志互查 | ✅ | V5 grep 命中，MDC 贯穿含后台线程；E2E ListAppender 断言 |
| SC-008 旧数据兼容零配置 | ✅ | 默认开启无任何配置项；AuditSchemaUpgradeTest 存量升级/幂等/新装三例；trace 为 null 旧行列表照常（TraceTimelineTest） |

## 质量门禁

`mvn verify` 全量门禁（Spotless + P3C + Checkstyle + SpotBugs/FindSecBugs + OWASP）：BUILD SUCCESS（见 T024）。

## 备注

- 处理路径新增两处 INFO 日志点（LLM 调用完成/工具执行完成，仅名称/耗时/成败，不含内容）——SC-007 的落地前提：此前成功路径零日志，「日志与审计互查」无从兑现。
- Redactor 正则容忍转义 JSON 形态（`\"password\":\"…\"`）——审计参数常为字符串内嵌 JSON，真机走查发现并回补测试。
