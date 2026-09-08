# Matrix / Element 渠道接入指南

Client-Server API **`/sync` 长轮询**（免公网回调）。`extra.homeserver` 为 homeserver 根 URL。

## 一、Homeserver 侧

1. 为 Bot 注册账号（如 `@oryx:example.com`），创建 access token。
2. 邀请 Bot 进房间；房间消息需提及 Bot MXID。
3. 把 homeserver 主机名加入 `http.allowed_domains`（自托管，不进默认清单）。

## 二、OryxOS 侧

```yaml
channels:
  - name: ops-matrix
    type: matrix
    app_id: ${MATRIX_BOT_USER}
    app_secret: ${MATRIX_ACCESS_TOKEN}
    agent: ops-agent
    extra:
      homeserver: ${MATRIX_HOMESERVER}
    enabled: true
```

## 三、Notify

`type: matrix`：`homeserver` + `token` + `room_id`。

## 四、实测清单

- 私聊 / 房间 @ 往返
- `notify` 进房间
