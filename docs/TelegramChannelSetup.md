# Telegram 机器人入站渠道接入指南

本文是 OryxOS Telegram 入站渠道的部署手册。以 Bot API **`getUpdates` 长轮询**接入（免公网回调 URL）；回复经 `sendMessage`。出站告警另见 Notify `type: telegram`。

> 范围：私聊文本/图片/语音/文件；群聊仅处理 `@Bot`。语音走现有 Whisper。

## 一、Telegram 侧

1. 与 [@BotFather](https://t.me/BotFather) 对话 `/newbot`，记下 **Bot Token** 与 **用户名**（如 `OryxOsBot`）。
2. 群聊：把 Bot 拉进群，并关闭 Privacy Mode（`/setprivacy` → Disable），否则收不到非 @ 之外的消息上下文；OryxOS 仍只处理 `@Bot`。
3. 凭证只走环境变量，禁止写入仓库。

## 二、OryxOS 侧

```bash
export TELEGRAM_BOT_TOKEN=123456:ABC...
export TELEGRAM_BOT_USERNAME=OryxOsBot
```

`.oryxos/channels.yaml`：

```yaml
channels:
  - name: ops-telegram
    type: telegram
    app_id: ${TELEGRAM_BOT_TOKEN}
    app_secret: ${TELEGRAM_BOT_USERNAME}
    agent: ops-agent
    enabled: true
```

出站白名单需包含 `api.telegram.org`。启动后 `GET /api/v1/channels/status` 期望 `CONNECTED`。

## 三、Notify

管理台 `type: telegram`：`token` + `chat_id`（私聊为用户数字 ID，群为负数 ID）。

## 四、实测清单

- 私聊往返
- 群 `@Bot` 回复带引用
- `notify` 打进指定 chat

未提供 Bot Token 时以假 HTTP 单测为准，不宣称生产联调完成。
