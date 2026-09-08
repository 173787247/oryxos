# 023-provider-fallback 真机验收报告

**Date**: 2026-09-02 | **环境**: WSL2 + fat JAR（`oryxos serve`，三 provider：mock + broken 指 127.0.0.1:1 + bad400 指本地 400 stub）+ sqlite 直查

## 走查结果（quickstart V1~V6）

| # | 场景 | 结果 | 证据 |
|---|------|------|------|
| V1 | 主败备成用户无感知 | ✅ | fb-agent（主 broken + fallback mock）invoke 返回 200、回复正常、响应带 traceId |
| V2 | 切换全程留痕可回放 | ✅ | llm_calls 该 trace 下 4 条（broken 失败×2 + mock 成功×2，provider/model 如实）；时间线 5 步同链按时间序（LLM 败→LLM 成→TOOL→LLM 败→LLM 成）；serve.log 两条 WARN「provider 切换: broken → mock」均带 [traceId] |
| V3 | 全部候选失败 | ✅ | doomed-agent invoke → HTTP 500，**29ms** 返回（R8 收敛内建重试后无退避，SC-007 达成）；无成功行 |
| V4 | 业务性失败不切换 | ✅（修复后复验） | bad400-agent（主 400 stub + fallback mock）→ HTTP 500 直接报错、仅 1 条 bad400 失败行、0 切换日志。**首跑暴露真 bug**：Spring AI 把 4xx 包成 NonTransientAiException（cause 链无状态码、message 前缀 "400 - "）导致误切——分类器补 message 前缀码解析后复验通过 |
| V5 | 流式首片段前切换透明 | ✅ | SSE 流首 `trace` 事件 → tool 事件 → 正常收尾，无 error 事件（主失败被切换吸收，客户端无感知） |
| V6 | prometheus 业务指标 | ✅ | `oryxos_fallback_switches_total{from="broken",to="mock"} 2`、`oryxos_llm_calls_total` 分 provider/model/outcome 序列、duration timer、`oryxos_tool_invocations_total{tool="save_memory"}`；**指标合计 5 = llm_calls 行数 5**（SC-005/SC-006 口径一致）；策略拦截计数由 E2E 验证 |

## SC 达成对照

| SC | 判定 | 依据 |
|----|------|------|
| SC-001 故障场景 100% 正常完成 | ✅ | V1/V5 + ProviderFallbackTest 9 例 + E2E 4 例 |
| SC-002 零声明回归零破坏 | ✅ | E2E 零声明单轮恰 2 条审计；全模块测试回归全绿。**R8 例外如实声明**：Spring AI 内建 RetryTemplate（10 次退避至 180s）收敛为单次尝试——失败上抛口径不变，隐形重试次数变化；不收敛则 SC-007 失控且挂死端点卡同步会话数分钟（详见 research R8） |
| SC-003 切换 100% 可回放 | ✅ | V2 审计成对 + 时间线同链 + WARN 带 trace；E2E ListAppender MDC 断言 |
| SC-004 边界零越界 | ✅ | V4（400 不切）+ 流式已出内容不切（单测 Flux 延迟构造钉死）+ FallbackClassifierTest 6 例分类表 |
| SC-005 指标与实际计数一致 | ✅ | V6 对照 + E2E 指标总数=审计行数断言 |
| SC-006 审计口径零变化 | ✅ | 每尝试一条即既有 record 调用路径；E2E 零声明基线对照 |
| SC-007 无额外等待 | ✅ | V3 全败 29ms 返回（两候选连接拒绝即返回，无退避） |

## 质量门禁

`mvn verify` 全量门禁：BUILD SUCCESS（见 T020）。

## 备注（实施中发现并修正）

- **Spring AI 异常包装形态**（V4 首跑暴露）：4xx 经 Spring AI 错误处理器变成 `NonTransientAiException("400 - {json}")`，cause 链上没有携带状态码的 RestClient 异常——分类器仅靠异常链提取会把 400 误判为"未知→可切"。补 message 前缀码解析 + 无码时信 Spring AI 的 Transient/NonTransient 二分；FallbackClassifierTest 补 5 个真机形态用例
- **R8 实施期裁决**：收敛 OpenAiChatModel 内建 RetryTemplate 为单次尝试（重试语义单层归 fallback）——V3 的 29ms 全败返回即其效果；行为差异已在 research R8 与本报告 SC-002 行如实声明
- **测试环境特性**：`@SpringBootTest` 默认禁用 metrics export（防测试打点外泄），E2E 需 `@AutoConfigureObservability`；真机 serve 不受影响（V6 直接可达）。Prometheus 文本格式标签按字母序输出，断言不得假定标签顺序
- mock provider usage 恒为 0 token → `oryxos_llm_tokens_total` 在纯 mock 场景合法缺席（契约明示形态），计数语义由 MicrometerMetricsRecorderTest 钉死
