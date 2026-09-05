# Slack 机器人入站渠道接入指南

本文是 OryxOS Slack 入站渠道的部署操作手册。架构对称飞书/企微/钉钉：以 **Socket Mode** WebSocket 主动连接 Slack（免公网回调 URL）；回复经 `chat.postMessage`。

> 范围：Slack App **Socket Mode**。出站 Notify / MCP Slack 与本入站渠道分离。MVP 仅文本私聊与群 `@`。

## 一、Slack 侧：创建 App 并启用 Socket Mode

1. 打开 [api.slack.com/apps](https://api.slack.com/apps)，Create New App → From scratch，选目标 Workspace。
2. **OAuth & Permissions** → Bot Token Scopes 至少：`chat:write`、`im:history`、`app_mentions:read`、`channels:history`（按需）。
3. **Socket Mode** → Enable → 生成 **App-Level Token**（`xapp-…`），scope 勾 `connections:write`。
4. **Event Subscriptions** → Enable → Subscribe to bot events：`message.im`、`app_mention`。
5. **Install to Workspace**，复制 **Bot User OAuth Token**（`xoxb-…`）。
6. 将 Bot 邀请进目标频道（群聊需 `@机器人`）。

   - ⚠️ 凭证只经环境变量注入 OryxOS，禁止写入仓库或明文配置文件。

## 二、OryxOS 侧：配置与启动

1. **凭证走环境变量**：

   ```bash
   export SLACK_BOT_TOKEN=xoxb-...
   export SLACK_APP_TOKEN=xapp-...
   # 可选核对：export SLACK_TEAM_ID=T...
   ```

2. **渠道绑定** `.oryxos/channels.yaml`（模板见 `config/channels.yaml.example`）：

   ```yaml
   channels:
     - name: ops-slack
       type: slack
       app_id: ${SLACK_BOT_TOKEN}       # Bot Token → chat.postMessage
       app_secret: ${SLACK_APP_TOKEN}   # App-Level Token → Socket Mode
       agent: ops-agent
       enabled: true
   ```

3. **出站域名白名单**：确保 `http.allowed_domains` 包含：
   - `slack.com` — Web API
   - `*.slack.com` — Socket Mode WSS

4. 启动后查渠道状态：`GET /api/v1/channels/status`，期望 `CONNECTED`。

## 三、使用方式

- **私聊**：在 Slack 中打开该 Bot 的 DM，直接发文本。
- **群聊**：将 Bot 拉入频道后 `@Bot + 问题`（平台推送 `app_mention`）。
- **联网检索**：须在绑定 Agent 的 `AGENT.md` `tools:` 中加入 `web_search` 等，见 Tool 文档。

## 四、与飞书/企微/钉钉的差异

| | 飞书 | 企微 | 钉钉 | Slack（本渠道） |
|--|------|------|------|-----------------|
| 凭证 | App ID / Secret | BotID / Secret | ClientId / Secret | Bot Token / App-Level Token |
| 连接 | SDK 长连接 | 企微 WS | Stream WS | Socket Mode WSS |
| 回复 | im API | 长连接发帧 | sessionWebhook | chat.postMessage |
| MVP 媒体 | 图/文件/音视频 | 同 | 同 | **仅文本**（后续再补） |

## 五、非目标（本期不做）

- 图片 / 文件 / 语音 / 视频入站
- Block Kit / 斜杠命令 / HTTP Events 公网回调
- MCP `@modelcontextprotocol/server-slack`（可另配）
- Notify `type=slack`
