# 026 验收记录

**日期**: 2026-09-08  
**范围**: 波次 0–5 代码 + 契约/归一化单测。真平台往返待你提供凭证后补。

## 单测（本机 JDK 21）

| 模块 | 结果 |
|------|------|
| ChannelConfigLoader extra 回写/解析 | 通过（POSIX 权限用例 Windows 跳过，既有） |
| VendorNotifyAdapter（含 slack/discord/telegram/whatsapp/teams/gchat/mattermost/matrix） | 29 通过 |
| ChannelInboundWebhookController（未知渠道 / 非 webhook → 404；挑战回写） | 3 通过 |
| telegram / whatsapp / teams / gchat / mattermost / matrix 契约 + 归一化 | 全部通过 |
| WhatsApp 24h 窗外硬拒绝 + 验签/挑战 | 4 通过 |

全仓 `mvn test` 另有既有失败：Windows 上 `MasterKeyResolverTest`（POSIX 0600）与 `AgentSkillStartupOrderTest`（symlink），与本变更无关。

## 真平台

未在本环境发现 Slack/Discord/Telegram/WhatsApp/Teams/GChat/Mattermost/Matrix 可用 Token，**未宣称 COMPLETE 联调**。

请按各 `docs/*ChannelSetup.md` 注入环境变量后：

1. Slack / Discord：`notify` 各打一条进真实频道（波次 0）
2. Telegram：私聊往返 + 群 @ + notify
3. WhatsApp：WABA 回调挑战 + 窗内往返 + 窗外拒绝
4. Teams：Azure Bot 1:1 / 频道 @
5. Google Chat：Workspace DM / 空间 @
6. Mattermost 然后 Matrix：自建实例私聊 / 房间 @
