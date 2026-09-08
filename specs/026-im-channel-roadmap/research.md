# Research: 026 IM 渠道

## Google Chat 入站形态（波次 4）

**决定**：Chat API **HTTP 端点**，不用 Cloud Pub/Sub。

**原因**：与波次 0 共享 `POST /api/v1/channels/inbound/{name}` 对齐；Pub/Sub 需额外 GCP 订阅与推送鉴权，MVP 体积更大。空间 @ 以官方 `argumentText` / annotations 判定。

## WhatsApp 24h 窗

**决定**：适配器硬拒绝窗外 `sendReply`，错误文案点名「只能发送已审核模板」。不在适配器内伪造模板发送成功。

## Teams JWT

**决定**：MVP 拆 Bot Framework Activity 并经 `serviceUrl` 回复；边缘 JWT 校验由 Azure Bot Service / 反代承担，不在 core 引入 OpenID 依赖。
