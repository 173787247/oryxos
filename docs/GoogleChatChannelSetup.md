# Google Chat 渠道接入指南

采用 **Chat API HTTP 端点**（不用 Cloud Pub/Sub）。回调：`POST /api/v1/channels/inbound/{name}`。空间消息仅当带 `argumentText`（即 @Bot）时进入编排。

## 一、Workspace 侧

1. Google Cloud 启用 Chat API，创建 Chat 应用 / 服务账号。
2. 管理控制台把 Bot 挂到空间；HTTP endpoint 指到 OryxOS inbound URL。
3. `app_secret` 使用可调用 Chat API 的 access token（服务账号换票由运维侧完成，本 MVP 不内嵌 JWT 签名）。

## 二、OryxOS 侧

```yaml
channels:
  - name: ops-gchat
    type: gchat
    app_id: ${GCHAT_BOT_NAME}
    app_secret: ${GCHAT_ACCESS_TOKEN}
    agent: ops-agent
    enabled: true
```

白名单：`chat.googleapis.com`。

## 三、Notify

`type: gchat`：空间 Incoming Webhook `url`。

## 四、实测清单

- DM / 空间 @Bot 往返
- `notify` 进空间
