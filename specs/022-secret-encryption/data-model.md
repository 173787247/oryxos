# Data Model: 密钥加密存储

**Feature**: 022-secret-encryption | **Date**: 2026-09-01

**零 schema 变更**——密文写入既有列（`providers.api_key`、`notify_channels.config` JSON 内敏感值），以 `enc:v1:` 前缀自判别。

## 密文形态

```text
enc:v1:<Base64( IV[12] ‖ ciphertext ‖ GCM-tag[16] )>
```

| 要素 | 语义 |
|------|------|
| `enc:v1:` | 版本化前缀：明文/密文判别（迁移幂等依据）+ 未来算法/密钥体系升级识别位 |
| IV | 每次加密随机生成 12 字节，随密文存储——同一明文两次加密密文不同 |
| GCM tag | 认证标签：篡改/截断即解密失败（边界场景「密文行被改坏」的检测机制） |
| 空值 | null / 空串不加密、不产生前缀，保持现状语义 |

## 核心契约（oryxos-core `secret/`）

### SecretCipher（接口，KMS/Vault 升级缝）

| 方法 | 语义 |
|------|------|
| `String encrypt(String plaintext)` | 明文 → `enc:v1:` 密文；null/空原样返回 |
| `String decrypt(String stored)` | 密文 → 明文；无前缀视为明文原样返回（迁移期兼容）；解密失败抛 `SecretDecryptException` |
| `boolean isEncrypted(String value)` | 前缀判别（迁移与守卫复用） |

### LocalMasterKeyCipher（本版本唯一实现）

AES-256-GCM，密钥来自 `MasterKeyResolver`。

### MasterKeyResolver（主密钥两档）

```text
ORYXOS_MASTER_KEY 环境变量（Base64 32 字节）
  ├─ 存在且合法 → 用之（文件档忽略）
  ├─ 存在但格式非法 → 启动异常（说明格式要求，不降级）
  └─ 不存在 → {oryxos.root}/master.key
       ├─ 文件存在 → 读取校验
       └─ 不存在 → 生成随机 32 字节写入，POSIX 0600
```

## 加密作用面

| 存储位置 | 字段 | 处理 |
|----------|------|------|
| `providers.api_key` | 整列 | save 加密 / toDef 解密 |
| `notify_channels.config`（JSON） | `password`/`passwd`/`secret`/`token`/`api_key`/`apikey`/`access_key`（大小写不敏感，与 021 Redactor 名录同源） | JSON 内逐项加解密；名录外原样 |
| `notify_channels.url` | — | 不加密（R5 边界裁决：地址语义，需完整可见可编辑） |
| `web_users.password_hash` / `api_keys.key_hash` | — | 已是不可逆哈希，不在范围 |

## 启动序列（SecretMigration，AuditSchemaUpgrade 同位）

```text
装配 cipher → 扫描两表：
  明文敏感值（无前缀且非空） → 加密回写，计数（日志「已加密 N 条凭证」）
  密文值 → 试解密：
    全部失败 → 密钥不匹配 → 拒启：「已有 N 条加密凭证无法解密。恢复：找回原密钥；或经管理台删除并重新录入凭证」
    部分失败 → 单行损坏 → WARN 定位条目，继续启动（该凭证使用时清晰报错，FR-010）
  全新库（无任何凭证行） → 直接通过（零配置可用）
```

## 掩码回显（web 展示层，与加密正交）

- `NotifyChannelView`：config 敏感项（同上名录）→ `****` + 末 4 位（复用 `ProviderView.mask` 确定性幂等口径）
- 更新判定：敏感项提交值 == 原值掩码 或 空 → 保持原值；否则按新值落库（加密由注册表收口）
- `ProviderView` 既有机制不动
