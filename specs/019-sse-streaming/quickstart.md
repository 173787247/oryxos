# Quickstart: SSE 流式响应验收

**Feature**: 019-sse-streaming | 协议详见 [contracts/sse-protocol.md](contracts/sse-protocol.md)

## 前置

```bash
mvn -q -DskipTests package
alias oryxos='java -jar oryxos-boot/target/oryxos-boot-*.jar'
# mock provider（无 key 可测流式：mock 已支持分段流出，research R7）
oryxos serve --port 8080 &
SID=$(curl -s -X POST localhost:8080/api/v1/sessions -H 'Content-Type: application/json' -d '{"profile":"<mock-agent>"}' | jq -r .data.sessionId)
```

## V1 — 回归零破坏（US1 场景 2 / SC-001）

```bash
curl -s -X POST localhost:8080/api/v1/sessions/$SID/messages -H 'Content-Type: application/json' \
  -d '{"content":"hi"}'
# 期望：一次性 JSON 统一信封，与 018 交付时逐字段一致
```

## V2 — 流式打字机（US1 场景 1/3 / SC-002/SC-003）

```bash
curl -N -s -X POST localhost:8080/api/v1/sessions/$SID/messages \
  -H 'Accept: text/event-stream' -H 'Content-Type: application/json' -d '{"content":"hi"}'
# 期望：Content-Type: text/event-stream；多个 event: token 逐段到达；
#       恰好一个 event: done（reply == 全部 delta 拼接）；连接正常关闭
```

## V3 — 工具过程事件与心跳（US2 场景 1/3）

```bash
# 用会触发工具调用的消息（如让 mock-agent 记忆偏好触发 save_memory）
curl -N -s ... -d '{"content":"记住我喜欢咖啡"}'
# 期望：tool_start/tool_end 事件（含工具名、success）夹在流中；
#       长静默期可见 ": ping" 注释行（默认 15s 间隔）
```

## V4 — 错误终结与前置失败（US2 场景 2 / FR-009）

```bash
curl -N -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/v1/sessions/no-such/messages \
  -H 'Accept: text/event-stream' -H 'Content-Type: application/json' -d '{"content":"hi"}'   # 404 JSON（流未开始）
# 流中失败：断掉 provider（错误 base-url 的真实 provider）发起流式 → 收到恰好一个 event: error 后连接关闭
```

## V5 — 断开不丢数据（US2 场景 4 / SC-004）

```bash
# 发起流式后立即 Ctrl+C / timeout 掐断 curl
timeout 0.5 curl -N -s ... -d '{"content":"讲个长故事"}' || true
sleep 5
curl -s localhost:8080/api/v1/sessions/$SID | jq '.data.messages[-1]'
# 期望：会话历史含完整回复；llm_calls/tool_invocations 照写（对照未断开的一次无差异）
```

## V6 — invoke 端点双路（US1 场景 6 / SC-008）

```bash
# 非流式与流式各一次，行为与会话消息端点一致
curl -s  -X POST localhost:8080/api/v1/agents/<name>/invoke ... -d '{"content":"hi"}'
curl -N -s -H 'Accept: text/event-stream' -X POST localhost:8080/api/v1/agents/<name>/invoke ... -d '{"content":"hi"}'
```

## V7 — 认证门禁复验（FR-010 / SC-005）

```bash
# 开 oryxos.web.apikey.enabled=true 后：
curl -N -s -o /dev/null -w '%{http_code}\n' -H 'Accept: text/event-stream' -X POST ...   # 无 Key → 401 JSON
curl -N -s -H "X-API-Key: $KEY" -H 'Accept: text/event-stream' -X POST ...              # 有 Key → 正常流式
```

## V8 — CLI 打字机（US3 场景 4 / SC-008）

```bash
oryxos chat --profile <mock-agent>
# 输入消息 → 期望：回复逐段打印（非整段一次吐出）；工具调用期间有单行状态提示
```

## V9 — 管理台打字机（US3 场景 1~3 / SC-006）

浏览器登录管理台 → Agent 会话聊天页发消息 → 回复逐段浮现、工具调用有提示；刷新后历史与打字机最终内容一致；构造错误场景显示可读提示且输入框可用。

## V10 — 审计一致性（FR-012 / SC-007）

```bash
# 同一消息流式与非流式各发一次，对比两次的审计写入
sqlite3 .oryxos/oryxos.db "SELECT count(*), sum(total_tokens IS NOT NULL) FROM llm_calls WHERE session_id='$SID';"
# 期望：记录条数口径一致；usage/duration 字段齐全
```

## 质量门禁

```bash
mvn verify   # 全量门禁 + 全部新老测试
```
