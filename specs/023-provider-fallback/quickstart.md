# Quickstart: Provider 失败切换与业务指标验收

**Feature**: 023-provider-fallback | 契约详见 [contracts/fallback-metrics.md](contracts/fallback-metrics.md)

## 前置

```bash
mvn -q -DskipTests package
alias oryxos='java -jar oryxos-boot/target/oryxos-boot-*.jar'
# 注册两个 Provider：broken（指向不通端口，必然连接失败）与 mock（备用，无 key 可用）
# Agent frontmatter：provider.name=broken + fallback: [{name: mock, model: mock-model}]
oryxos serve --port 8080 &
```

## V1 — 主败备成，用户无感知（US1 / SC-001）

```bash
curl -s -X POST localhost:8080/api/v1/agents/<agent>/invoke -H 'Content-Type: application/json' \
  -d '{"content":"记住我喜欢咖啡"}'
# 期望：200，回复来自 mock（备用），data.traceId 可取
```

## V2 — 切换全程留痕可回放（US2 / SC-003）

```bash
sqlite3 oryxos.db "SELECT provider, model, success, error_message FROM llm_calls ORDER BY id DESC LIMIT 3;"
# 期望：broken 失败尝试与 mock 成功尝试各一条（每轮 2 次 LLM 调用 → 主败记录 2 条失败 + 2 条成功…以实际轮数为准）
curl -s localhost:8080/api/v1/audit/trace/<V1的traceId>
# 期望：时间线里 broken 失败 LLM 步与 mock 成功 LLM 步同链按时间序
grep "provider 切换" serve.log
# 期望：WARN「provider 切换: broken → mock」且行内带 [traceId]
```

## V3 — 全部候选失败，最后错误上抛（US1 场景 2）

```bash
# Agent2：主 broken + fallback broken2（同样不通）→ invoke
# 期望：错误响应（现状口径），不无限重试；llm_calls 两条失败记录；无成功行
```

## V4 — 业务性失败不切换（US1 场景 4 / SC-004）

```bash
# 起一个恒返回 400 的 stub 端点（python 一行）注册为 bad400 provider；Agent3：主 bad400 + fallback mock
# 期望：直接报错不切换；llm_calls 仅 1 条失败记录；无 mock 尝试、无切换日志
```

## V5 — 流式首片段前切换透明（US1 场景 5 / SC-001）

```bash
curl -N -s -X POST localhost:8080/api/v1/agents/<agent>/invoke \
  -H 'Accept: text/event-stream' -H 'Content-Type: application/json' -d '{"content":"hi"}' | head -12
# 期望（主 broken + 备 mock）：流首 trace 事件 → token 流正常 → done；无 error 事件（首片段前的失败已被切换吸收）
```

## V6 — 监控端点业务指标（US3 / SC-005/SC-006）

```bash
curl -s localhost:8080/actuator/prometheus | grep -E "^oryxos_"
# 期望：oryxos_llm_calls_total{provider="broken",outcome="failure"} 与 {provider="mock",outcome="success"} 计数
#       与 llm_calls 表行数一致；oryxos_fallback_switches_total{from="broken",to="mock"} ≥ 1；
#       oryxos_tool_invocations_total / oryxos_llm_tokens_total 在位
# 配 GLOBAL_DENY 触发一次拦截 → oryxos_policy_blocks_total 递增
sqlite3 oryxos.db "SELECT COUNT(*) FROM llm_calls;"   # 与 oryxos_llm_calls_total 各序列之和对照（SC-006 口径不变）
```

## 质量门禁

```bash
mvn verify
```
