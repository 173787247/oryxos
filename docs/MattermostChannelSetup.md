# Mattermost 渠道接入指南

私有化优先于 Matrix。入站用 **Outgoing Webhook**（表单 POST 到共享 inbound URL）；回复走 `POST /api/v4/posts`。

## 一、Mattermost 侧

1. 系统控制台 → Integrations → Outgoing Webhooks：Callback URL = `https://<host>/api/v1/channels/inbound/ops-mattermost`，记下 Token。
2. Trigger Word 设为 `@你的Bot用户名`（频道 @ 规则）。
3. 发帖用同一 Token 或 Personal Access Token（当前 `app_secret` 兼验证入站 token 与 Bearer）。
4. `extra.base_url` 为站点根（如 `https://mm.example.com`），需加入 `http.allowed_domains`。

## 二、OryxOS 侧

```yaml
channels:
  - name: ops-mattermost
    type: mattermost
    app_id: ${MATTERMOST_BOT_USERNAME}
    app_secret: ${MATTERMOST_TOKEN}
    agent: ops-agent
    extra:
      base_url: ${MATTERMOST_BASE_URL}
    enabled: true
```

自托管域名不进默认白名单，请在部署配置里显式加入。

## 三、Notify

`type: mattermost`：Incoming Webhook `url`。

## 四、实测清单

- 私聊 / 房间 @ 往返
- `notify` 进房间
