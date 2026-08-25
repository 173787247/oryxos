# Quickstart: 飞书 IM 入站渠道验证指南（017）

对应 spec 的 4 个 User Story 与关键 SC 的端到端验证路径。契约与数据模型见 [contracts/](./contracts/) 与 [data-model.md](./data-model.md)。

## 前置条件

1. **飞书侧**（企业自建应用）：
   - 创建自建应用，记下 App ID / App Secret；
   - 开通权限：`im:message`（收发）、`im:message.p2p_msg`（私聊）、`im:message.group_at_msg`（群 @）；
   - 事件订阅选择「**使用长连接接收事件**」，订阅 `im.message.receive_v1`；
   - 发布应用版本并设可见范围；把机器人拉进一个测试群。
2. **OryxOS 侧**：
   ```bash
   export FEISHU_APP_ID=cli_xxx
   export FEISHU_APP_SECRET=xxx
   ```
   - `config/application.yml` 的 `http.allowed_domains` 含 `*.feishu.cn`（本特性任务会补上）；
   - `.oryxos/agents/ops-agent/` 存在（任意已配 Provider 的 Agent）。
3. **网络**：仅需出方向可达 `open.feishu.cn`（长连接免公网回调）。

## 启动与配置

```bash
# 写入渠道绑定（或经 REST POST /api/v1/channels）
cat > .oryxos/channels.yaml <<'EOF'
channels:
  - name: ops-feishu
    type: feishu
    app_id: ${FEISHU_APP_ID}
    app_secret: ${FEISHU_APP_SECRET}
    agent: ops-agent
    enabled: true
EOF

mvn -q -pl oryxos-boot -am package -DskipTests   # 或既有构建方式
java -jar oryxos-boot/target/oryxos-boot-*.jar serve
```

**期望**：启动日志出现渠道上线；`curl localhost:8080/api/v1/channels/status` 返回 `state: CONNECTED`（US3-AS1）。

## 验证场景

### V1 私聊问答闭环（US1 / SC-001 / SC-005）
飞书私聊机器人发「服务器磁盘告警了怎么处理？」→ 收到 Agent 完整推理回答；追问「第二步的命令具体怎么写？」→ 回答承接上文。
校验审计：`sqlite3 oryxos.db "select session_id from sessions where channel='feishu'"` 出现 `feishu:<open_id>:ops-agent`。

### V2 群聊 @ 独立问答（US2 / SC-002 / SC-009）
- 群里 `@机器人 昨晚的发布为什么回滚了？` → 群内收到引用原消息的回复；
- 两人先后 @ 问不同问题 → 回答互不携带彼此上下文；
- 发不含 @ 的消息 → 零响应、零留痕（`sessions`/`agent_executions` 无新增行）。

### V3 去重与解耦（SC-004 / FR-008）
问一个需要多次工具调用的慢问题 → 飞书 3 秒内不因超时重发导致重复回答（仅 1 条最终回答）；超过处理中阈值时先收到「处理中」提示。

### V4 配置错误点名报错（US3 / SC-008）
```bash
unset FEISHU_APP_SECRET && 重启   # → status 显示 ERROR：点名 app_secret 未解析；其余功能正常
# 把 agent 改成不存在的名字 → 点名「绑定的 Agent xxx 不存在」，渠道不上线
```

### V5 免重启热更（FR-013）
服务运行中 `PUT /api/v1/channels/ops-feishu` 改绑另一 Agent → 无重启，下一条私聊由新 Agent 回答。

### V6 契约可扩展性（US4 / SC-007）
```bash
mvn -q test -pl oryxos-core -Dtest=InboundMessageServiceContractTest   # 测试桩档全绿
mvn -q verify                                                          # 全量门禁
git diff --stat <契约建立提交> -- oryxos-core/                          # 期望：空（桩渠道零 core 修改）
```

### V7 非文本与超长（FR-009）
私聊发一张图片 → 收到「当前仅支持文本提问」；构造超长回答（让 Agent 输出长文）→ 分段送达内容不丢。

## 期望结果汇总

| 场景 | 通过标准 |
|------|---------|
| V1 | 两轮问答连续；sessions 落 `channel=feishu` |
| V2 | @ 100% 响应且互不串扰；非 @ 零留痕 |
| V3 | 重复推送仅 1 条回答；慢任务先收「处理中」 |
| V4 | 两类配置错误均点名报错、不带病上线、不影响其余功能 |
| V5 | 变更即生效，无重启 |
| V6 | 契约测试两档全绿；桩渠道 core 零 diff |
| V7 | 能力说明回复；分段完整送达 |
