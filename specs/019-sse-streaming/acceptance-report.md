# 验收报告: SSE 流式响应（019）

**Date**: 2026-08-27 | **验收方式**: 自动化测试（22 用例）+ fat JAR 真机走查（quickstart V1~V10）+ Chromium 无头浏览器管理台走查

## 自动化测试

| 套件 | 用例 | 结果 |
|------|------|------|
| `ReActLoopStreamTest`（core） | 4 | ✅ 全过（打点顺序/tool 成对/NOOP 等价/多轮连续） |
| `ProviderStreamTest`（provider） | 5 | ✅ 全过（分段聚合/tool-call 增量合并/降级/中断落账/mock 分段） |
| `SseStreamingTest`（web） | 7 | ✅ 全过（分流/线格式/拼接一致/终结唯一/tool 有序/流前 404 JSON/心跳） |
| `CliChannelStreamTest`（cli） | 3 | ✅ 全过（逐段打印/工具提示/回落判定） |
| `SseStreamingE2ETest`（boot，真实 HTTP+SQLite+mock 流式） | 3 | ✅ 全过（全链路/断开落库/门禁复验） |

## 真机走查（quickstart V1~V10）

环境：WSL2，fat JAR `oryxos-boot-0.1.3-RELEASE.jar`，mock provider（已支持分段流式），独立 scratch 工作区。

| 走查 | 观测 | 结论 |
|------|------|------|
| V1 回归零破坏 | 不带 Accept → 一次性 JSON 统一信封，与 018 交付时一致 | ✅ |
| V2 流式打字机 | `Content-Type: text/event-stream`；5 个 `token` 事件（4 字切段）逐段到达；恰好一个 `done`，`reply` == 全部 delta 拼接 | ✅ |
| V3 工具过程事件 | `tool_start`/`tool_end`（save_memory, success=true）成对出现在 token 之前，顺序反映真实执行 | ✅ |
| V4 流前失败 | 会话不存在 + `Accept: text/event-stream` → `404` JSON（非 SSE） | ✅ |
| V5 断开不丢数据 | `timeout 0.3 curl` 掐断后：`llm_calls` 4→6（两轮照写）、会话最后一条为完整 assistant 回复 | ✅ |
| V6 invoke 双路 | 非流式 JSON 正常；流式 5 个 token 事件，与会话端点同口径 | ✅ |
| V7 门禁复验 | `apikey.enabled=true` 时无 Key 流式请求 → `401` JSON（统一信封） | ✅ |
| V8 CLI 打字机 | `oryxos chat` 输出含 `[工具 save_memory 完成]` 状态提示 + 逐段回复（管道捕获；交互终端即打字机） | ✅ |
| V9 管理台打字机 | Chromium 走查：会话 tab Ctrl+Enter 发送 → 请求 200 `text/event-stream`、中间态捕捉到部分内容、最终回复完整渲染（截图留证） | ✅ |
| V10 审计一致性 | E2E 断言：同一消息流式与非流式 `llm_calls` 条数一致（2=2）；V5 已验断开场景照写 | ✅ |

**心跳（FR-007）**：mock 工具瞬时完成、真机无 15s 静默期；由单测钉死（间隔调 1s + 2.5s 静默 → `: ping` 出现）。

## SC 达成情况

| SC | 口径 | 结论 |
|----|------|------|
| SC-001 回归零破坏 | V1 真机 + SseStreamingTest 非流式用例 + `mvn verify` 全量既有测试通过 | ✅ |
| SC-002 首段先达 | 弱化版自动化（E2E：token 数 >1 且首 token 早于 done）+ 真机 V2 可见逐段到达；mock 无真实生成延迟，30% 比例待真实 provider 部署场景复核（analyze G1 裁决） | ✅（弱化口径） |
| SC-003 事件完整性 | 终结唯一与拼接一致由 web 测试 + E2E + 真机三层钉死 | ✅ |
| SC-004 断开完整率 | E2E 轮询断言 + 真机 V5（llm_calls 4→6、回复落库） | ✅ |
| SC-005 门禁生效 | E2E `apiKeyGate` + 真机 V7 | ✅ |
| SC-006 管理台打字机 | V9 浏览器走查 PASS（流式请求、中间态、最终一致、截图） | ✅ |
| SC-007 审计一致 | E2E 同消息双路条数相等；provider 单测钉审计口径复用 | ✅ |
| SC-008 CLI/invoke | V8 CLI 打字机 + V6 invoke 双路 | ✅ |

## 实现要点与发现

- **宪法 VII 落地**：Web 侧未用 SseEmitter/async servlet——controller 在虚拟线程上同步直写 response 输出流；Reactor 类型经 `Flux.toIterable()` 降为同步迭代且封死在 provider 实现方法内部。
- **走查发现并修复的存量缺陷**：`GlobalExceptionHandler` 的错误响应未显式设定 JSON content-type——客户端 `Accept` 只有 `text/event-stream` 时内容协商失败、异常原样上抛（500 而非承诺的 404/400 JSON）。修复：三个相关 handler 显式 `contentType(APPLICATION_JSON)` 跳过协商（FR-009 的真机验证即 V4）。
- **Reactor BlockingIterable 语义**：error 就位后丢弃未消费队列项——provider 测试按此语义断言（不返回残缺结果 + 失败落账），生产中消费与网络到达同步推进不受影响。
- **oryxos-channel-cli 补齐测试基建**：模块此前无测试依赖，pom 增加 `spring-boot-starter-test`（test scope）。

## 质量门禁

`mvn verify`（Spotless + P3C + Checkstyle + SpotBugs/FindSecBugs + OWASP Dependency-Check）：**BUILD SUCCESS**，全仓测试 0 失败 0 错误。过程记录：① 既有 `AgentServiceTest` 按三参 `reActLoop.run` stub/verify——`AgentService` 改为 NOOP 时仍走三参原入口（保持既有交互契约，语义与四参 NOOP 等价）；② FindSecBugs 对 provider 报 2 个 CRLF（sanitize 消毒不跨方法追踪，与 chat 同口径）与 5 个冗余判空（流式 chunk 边界形态因 provider 而异，防御性判空有意保留），均按项目既有模式以带理由的 `SuppressFBWarnings` 落案；③ Spotless 格式经 `spotless:apply` 统一。
