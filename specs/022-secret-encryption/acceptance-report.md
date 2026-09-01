# 022-secret-encryption 真机验收报告

**Date**: 2026-09-01 | **环境**: WSL2 + fat JAR（`oryxos serve`，mock provider，v0.1.4）+ sqlite 直查 + 缓存 Chromium 浏览器走查

## 走查结果（quickstart V1~V7）

| # | 场景 | 结果 | 证据 |
|---|------|------|------|
| V1 | 零配置首启 | ✅ | 未配置任何环境变量启动正常；`.oryxos/master.key` 自动生成、权限 `-rw-------`（0600）；日志「主密钥来源：首次启动已自动生成…（仅属主可读，本地档）」 |
| V2 | 落库即密文 | ✅ | 录入 Provider 与 email 渠道后 sqlite 直查：`api_key` 列 `enc:v1:l4w15/…`；config 中 password 为 `enc:v1:` 密文、host/from/port 保持明文；全库 grep 无明文泄露。创建响应即时掩码（`****-key`/`****p@ss`） |
| V3 | 功能回归 | ✅ | mock 对话全链（读 provider → LLM → save_memory → 收尾）回复正常，与加密前行为一致 |
| V4 | 存量明文迁移 | ✅ | 手工 UPDATE 回明文 → 重启：日志「已加密 1 条凭证（022 存量明文迁移）」，列值回到 `enc:v1:`，无明文残留 |
| V5 | 密钥丢失拒启指路 | ✅ | 删 master.key 启动：退出码 1，文案「已有 2 条加密凭证无法解密。可能：主密钥丢失或被更换。恢复：找回原密钥（ORYXOS_MASTER_KEY 或 .oryxos/master.key）；或经管理台删除并重新录入凭证」；恢复钥匙后正常启动 |
| V6 | 环境变量档三态 | ✅ | (a) 错误 env 钥匙 → 拒启同 V5 口径；(b) env=文件同值 → 正常启动且日志「主密钥来源：ORYXOS_MASTER_KEY 环境变量（生产档）」；(c) `ORYXOS_MASTER_KEY=short-bad` → 退出码 1，文案含格式要求与 `openssl rand -base64 32` 提示 |
| V7 | 管理台不回显明文 | ✅ | 浏览器（Chromium headless）：渠道/Provider 接口与页面全文均无明文、掩码在位；掩码原样 PUT 后库中仍为密文且掩码串未落库（未修改判定全链）；截图 v7-secret.png 留档 |

## SC 达成对照

| SC | 判定 | 依据 |
|----|------|------|
| SC-001 db 文件外流不泄凭证 | ✅ | V2 直查全密文 + SecretStorageTest/E2E 断言（含 YAML 播种路径经 save 自动加密） |
| SC-002 存量升级一次启动完成加密且功能一致 | ✅ | V4 迁移日志与幂等 + V3 功能回归 + E2E `功能回归_mock对话全链照常` |
| SC-003 零配置开箱即用 | ✅ | V1 全程无配置无感知 + E2E 0600 断言 |
| SC-004 故障 100% 启动拦截且指路 | ✅ | V5/V6a/V6c 三种故障全部退出码 1 + 完整恢复文案；SecretStorageTest 直调守卫断言（全坏拒启/部分坏 WARN 区分） |
| SC-005 管理台/接口零明文 | ✅ | V7 页面+接口全文扫描；controller 测试 3 例 + E2E 掩码断言 |
| SC-006 开销无感（启动 <1s 增量） | ✅ | AES 单值微秒级；启动迁移毫秒级（凭证数十以内）；走查启动时长与 021 时段无可感差异 |
| SC-007 威胁边界如实成文 | ✅ | CliGuide「5.0 主密钥」节：防 db 单独外流；文件档不防整机沦陷、生产用环境变量档 |

## 质量门禁

`mvn verify` 全量门禁：BUILD SUCCESS（见 T021）；FindSecBugs crypto 规则（STATIC_IV 等）以「每次随机 IV」实现自然通过，零 Suppress。

## 备注（实施中发现并修正）

- **随机 IV 的断言口径**：掩码原样 PUT 后「原值保留」应断言解密值而非密文字节——未修改时原明文重新加密、IV 随机使密文必变（E2E 首跑暴露，语义正确、断言修正）。
- **极老库自愈**：notify_channels 的 config 列由晚于本迁移的 CommandLineRunner 补齐——SecretMigration 对"列未就绪"WARN 跳过、下次启动自愈（providers 守卫不受影响）。
- **构建陷阱**：仓库版本已升 0.1.4-RELEASE，走查初期误用旧 0.1.3 fat JAR 导致"零效果"假象——按新版本文件名重打包后全部复验通过。
