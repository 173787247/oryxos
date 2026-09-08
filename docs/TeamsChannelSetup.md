# Microsoft Teams 渠道接入指南

Azure Bot / Bot Framework 入站走共享 webhook：`POST /api/v1/channels/inbound/{name}`。MVP **纯文本 + 引用**，不做自适应卡片。

## 一、Azure 侧

1. 在 Azure 创建 Bot 应用，记下 Application (client) ID、client secret、Directory (tenant) ID。
2. Messaging endpoint：`https://<host>/api/v1/channels/inbound/ops-teams`。
3. 将 Bot 加入一个 Team 频道；频道消息需 @Bot。
4. 生产环境建议由 Azure Bot Service 在边缘校验 JWT；OryxOS MVP 拆 Activity 并回复。

## 二、OryxOS 侧

```yaml
channels:
  - name: ops-teams
    type: teams
    app_id: ${TEAMS_APP_ID}
    app_secret: ${TEAMS_APP_SECRET}
    agent: ops-agent
    extra:
      tenant_id: ${TEAMS_TENANT_ID}
    enabled: true
```

白名单：`login.microsoftonline.com`、`*.trafficmanager.net`（`serviceUrl`）。

## 三、Notify

`type: teams`：Incoming Webhook `url`（Office 365 连接器）。与入站 Bot 是两套配置。

## 四、实测清单

- 1:1 与频道 @Bot 各一条往返
- `notify` 打到 team/channel webhook
