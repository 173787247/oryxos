# Contract: Provider 失败切换语义与业务指标

**Feature**: 023-provider-fallback | **Date**: 2026-09-02 | 原则五：接口即承诺

## 1. 切换语义承诺

1. **边界**：切换发生在「单次 LLM 调用」层面；ReAct 轮次（max_iterations）口径不变；每次调用独立从主 Provider 开始（无跨请求健康记忆）。
2. **顺序**：严格按 `provider.fallback` 声明顺序尝试；声明什么执行什么（同名冗余照常尝试，不做去重魔法）。
3. **触发**：仅 Provider 侧故障（网络/超时/5xx/429/401/403/408 与无法归类的运行时异常）触发切换；400 类业务性失败不切换。
4. **终态**：候选耗尽以**最后一次**错误按现状口径上抛——不吞错、不无限重试、无退避等待（最坏时延 = 候选数 × 单次超时）。
5. **候选无效**：引用未注册/已删除 Provider 的候选跳过并 WARN，不中断序列、不产生失败审计。
6. **流式**：首个内容片段送出前的失败可切换（客户端无感知）；内容已流出后不切换，按 019 既有 `error` 事件语义收尾。
7. **零声明零变化**：未声明 fallback 的 Agent 行为（含错误反馈口径）与本特性交付前完全一致。

## 2. 留痕承诺（治理底线：任何自动行为可回放）

- 每次尝试各写一条 `llm_calls`：provider/model 按实际尝试值如实、失败带错误信息、成功带 usage/成本——主败备成 = 恰两条。
- 同轮全部尝试共享该轮 trace（021）：`GET /api/v1/audit/trace/{id}` 时间线上主备 LLM 步同链按时间序可见。
- 切换时一条 WARN 日志：`provider 切换: <from> → <to>`，MDC 携带 traceId（与审计互查）。

## 3. 业务指标承诺（/actuator/prometheus，既有端点增量）

| 指标 | 类型 | 标签 |
|------|------|------|
| `oryxos_llm_calls_total` | counter | provider, model, outcome |
| `oryxos_llm_call_duration_seconds` | timer | provider, model |
| `oryxos_llm_tokens_total` | counter | provider, model, type |
| `oryxos_tool_invocations_total` | counter | tool, outcome |
| `oryxos_policy_blocks_total` | counter | tool |
| `oryxos_fallback_switches_total` | counter | from, to |

- `oryxos_llm_calls_total` 与 `llm_calls` 表行数同口径（每尝试计一）；指标供监控聚合，审计供精确回放，二者正交。
- 指标采集失败不影响主链路；未发生对应事件时指标缺席或为零，均为正常形态。
- 指标命名与标签自本版本固化，后续只增不改。

## 4. 兼容性承诺

- frontmatter 纯增量字段（`provider.fallback` 可空）；ProviderService 契约、审计契约、REST/SSE 契约零改动。
- 管理台零新页面；`oryxos chat` 等无监控上下文场景自动降级为不采集（功能不受影响）。
