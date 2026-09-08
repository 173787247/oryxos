# Quickstart: 审计 Trace 串联与脱敏验收

**Feature**: 021-audit-trace | 契约详见 [contracts/trace-api.md](contracts/trace-api.md)

## 前置

```bash
mvn -q -DskipTests package
alias oryxos='java -jar oryxos-boot/target/oryxos-boot-*.jar'
oryxos serve --port 8080 &   # mock provider + mock-agent（save_memory 两轮脚本）
```

## V1 — REST 回传与全链路回放（US1/US2 / SC-002/SC-004）

```bash
TRACE=$(curl -s -X POST localhost:8080/api/v1/agents/<agent>/invoke -H 'Content-Type: application/json' \
  -d '{"content":"记住我喜欢咖啡"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['traceId'])")
echo $TRACE   # 期望：UUID
curl -s localhost:8080/api/v1/audit/trace/$TRACE
# 期望：found=true；steps=[LLM, TOOL(save_memory), LLM] 时间序；summary 含 totalTokens/totalDurationMs/steps=3
```

## V2 — 隔离与旧数据（US1 场景 2/5 / SC-003）

```bash
# 同 Agent 再发一条 → 新 traceId；两 ID 各查各的、步数互不混串
# 升级前的旧库（或手工置空 trace_id 行）：按会话/时间查询照常返回
```

## V3 — SSE 流式回传（US2 / SC-004）

```bash
curl -N -s -X POST localhost:8080/api/v1/sessions/<sid>/messages \
  -H 'Accept: text/event-stream' -H 'Content-Type: application/json' -d '{"content":"hi"}' | head -20
# 期望：首个业务事件为 event: trace（data 含 traceId）；done 负载同带 traceId；用该 ID 查审计命中本轮
```

## V4 — 执行历史与定时触发（US2 场景 3）

```bash
curl -s -X POST localhost:8080/api/v1/agents/<agent>/trigger -H 'Content-Type: application/json' -d '{}'
sleep 3
curl -s localhost:8080/api/v1/agents/<agent>/executions | python3 -m json.tool | grep -A2 traceId
# 期望：执行记录含 traceId；按该 ID 查审计命中后台执行的全链路
```

## V5 — 日志互查（US2 场景 4 / SC-007）

```bash
grep "$TRACE" <serve 日志>
# 期望：本轮关键日志行（LLM 调用/工具执行）带 [traceId]；与时间线步骤可对应
```

## V6 — 失败与被拦步骤入链（US1 场景 4 + 020 联动）

```bash
# 配 GLOBAL_DENY save_memory 后再 invoke → 时间线中 TOOL 步 success=false、blockedBy=policy；链路完整
```

## V7 — 脱敏（US3 / SC-006）

```bash
# 让工具参数携带敏感形态（如给 mock-agent 发含 "password":"p@ss123"、Bearer sk-abc… 的消息，
# mock 会把消息原文作为 save_memory 参数）→
curl -s localhost:8080/api/v1/audit/trace/<该轮ID> | grep -o '"inputSummary":"[^"]*"'
# 期望：展示值中敏感段为 前4字符+****；同时：
sqlite3 .oryxos/oryxos.db "SELECT input_json FROM tool_invocations WHERE trace_id='<该轮ID>';"
# 期望：库中原文完整；不含敏感形态的普通内容原样展示
```

## V8 — 管理台（US3 / SC-005/SC-008）

浏览器 `/admin/` → 报表页：输入 trace ID → 时间线视图（步骤/类型/耗时/成败、LLM token 与成本、TOOL 脱敏摘要）；LLM/工具明细行可见 traceId 并可点击查询；无效 ID 显示"未找到"。**v0.3 Demo 后半段**：触发调用 → 拿 traceId → 管理台查到完整链路与成本。

## 质量门禁

```bash
mvn verify
```
