# Implementation Plan: 密钥加密存储

**Branch**: `022-secret-encryption` | **Date**: 2026-09-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/022-secret-encryption/spec.md`

## Summary

落库凭证从明文变密文：`SecretCipher` 契约进 core（依赖倒置，本版本落地 `LocalMasterKeyCipher`——AES-256-GCM + 每次随机 IV，密文形态 `enc:v1:<base64>`，零新依赖走 JDK 内置 crypto）；加解密收口在两个既有 Jpa 注册表（`JpaProviderRegistry` / `JpaNotifyChannelRegistry`）的 save/toDef——**Registry 契约零改动**（021 同款纪律）。主密钥两档：`ORYXOS_MASTER_KEY` 环境变量优先，缺省用 `{oryxos.root}/master.key`（首启自动生成、POSIX 0600，`.oryxos/` 已整体 gitignore）。启动时幂等迁移存量明文（按前缀判别）+ 密钥守卫（有密文解不开→拒启指路）。管理台补通知渠道 config 敏感项掩码回显 + "掩码原样=未修改"判定（复刻 ProviderView 既有机制）。零新表、零新列、零新依赖、零新模块。

## Technical Context

**Language/Version**: Java 21（JDK 内置 `javax.crypto` AES-GCM，无 BouncyCastle 等新依赖）

**Primary Dependencies**: 无新增；掩码机制复刻 `ProviderView.mask`（确定性 + 幂等，"未修改"判定基础）

**Storage**: 零 schema 变更——密文写入既有 `providers.api_key` 与 `notify_channels.config`（JSON 内敏感值逐项加密）；`enc:v1:` 前缀自判别明文/密文

**Testing**: JUnit 5——core（Cipher 往返/密钥解析/格式校验）、storage（注册表加解密透明性/坏行不拖垮 list）、web（掩码回显/未修改判定）、boot E2E（明文库迁移/零配置首启/密钥丢失拒启/功能回归）；`mvn verify` 全量门禁

**Target Platform**: Linux server（WSL2 验证 POSIX 文件权限）

**Project Type**: Maven 多模块单体——core（契约+本地实现+主密钥解析）、storage（两注册表收口+启动迁移）、cli（装配+守卫接线）、web（通知渠道掩码补缺）、boot（E2E）

**Performance Goals**: AES-GCM 单值加解密微秒级；启动迁移一次全表扫描（凭证行数十以内），SC-006 启动增时 <1s 裕量极大

**Constraints**: Registry 契约（core 接口）零改动；密文进既有列（长度 TEXT 无忧）；单条坏行不拖垮整体（FR-010）；主密钥不入日志/响应（FR-009）

**Scale/Scope**: 约 4 个新文件 + 6 个既有文件小改

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 评估 | 结论 |
|------|------|------|
| I 自实现 ReAct | 不涉及 | ✅ |
| II Spring AI 边界 | 不涉及 | ✅ |
| III Provider 显式映射 | 映射逻辑不动；ChatModel 构建拿到的是解密后 ProviderDef，无感知 | ✅ |
| IV 目录=Agent / Skill | 不涉及（AGENT.md `${ENV}` 路径明确不动，FR-011） | ✅ |
| V 审计 Day One | 不涉及审计写入口径；审计表内容不加密（021 裁决，FR-011） | ✅ |
| VI 安全是地基 | 本特性即该原则的欠账清偿：凭证明文落库让步收回；白名单/最小权限机制不动 | ✅ |
| VII 同步 + 虚拟线程 | 加解密为同步纯计算，无异步引入 | ✅ |
| VIII 状态外置 / 手工 schema | 零 schema 变更（密文进既有列）；master.key 是部署态密钥非业务状态 | ✅ |
| 模块约束 | SecretCipher 契约 + 本地实现进 core（storage/cli 消费方都已依赖 core）；不新建模块，无循环依赖 | ✅ |

**Phase 1 设计后复评**: 设计未引入新违背项，全部通过。无需 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/022-secret-encryption/
├── plan.md              # 本文件
├── research.md          # Phase 0：7 项技术裁决（R1~R7）
├── data-model.md        # Phase 1：密文形态 + 主密钥解析 + 敏感字段名录
├── quickstart.md        # Phase 1：V1~V7 验收走查
├── contracts/
│   └── secret-storage.md # Phase 1：加密承诺 + 掩码交互 + 故障语义
└── tasks.md             # Phase 2（/speckit-tasks 生成）
```

### Source Code (repository root)

```text
oryxos-core/
├── src/main/java/io/oryxos/core/secret/
│   ├── SecretCipher.java            # 新增：契约（encrypt/decrypt/isEncrypted）——KMS/Vault 换实现不动调用方
│   ├── LocalMasterKeyCipher.java    # 新增：AES-256-GCM 实现，enc:v1: 前缀，随机 IV
│   └── MasterKeyResolver.java       # 新增：ORYXOS_MASTER_KEY 优先 → master.key 文件（缺则生成 0600）
└── src/test/java/io/oryxos/core/secret/
    ├── LocalMasterKeyCipherTest.java # 新增：往返/前缀判别/篡改检测/空值语义
    └── MasterKeyResolverTest.java    # 新增：两档优先级/自动生成权限/格式校验报错

oryxos-storage/
├── src/main/java/io/oryxos/storage/
│   ├── JpaProviderRegistry.java     # 修改：save 加密 apiKey、toDef 解密（坏行 null + WARN）
│   ├── JpaNotifyChannelRegistry.java # 修改：config 敏感项逐项加解密（名录常量共享）
│   └── SecretMigration.java         # 新增：启动幂等迁移 + 密钥守卫（有密文解不开→拒启指路）
└── src/test/java/io/oryxos/storage/
    └── SecretStorageTest.java       # 新增：落库为密文/读出为明文/迁移幂等/守卫拒启/坏行隔离

oryxos-web/
├── src/main/java/io/oryxos/web/
│   ├── controller/dto/NotifyChannelView.java   # 修改：config 敏感项掩码（复刻 ProviderView.mask 口径）
│   └── controller/NotifyChannelApiController.java # 修改：更新时掩码原样/留空=保持原值
└── src/test/java/io/oryxos/web/controller/
    └── NotifyChannelApiControllerTest.java     # 修改：追加掩码回显与未修改判定用例

oryxos-cli/
└── src/main/java/io/oryxos/cli/OryxOsRuntime.java # 修改：装配 cipher 注入两注册表 + 启动跑 SecretMigration

oryxos-boot/
└── src/test/java/io/oryxos/boot/SecretEncryptionE2ETest.java # 新增：零配置首启/明文迁移/功能回归/密钥丢失拒启
```

**Structure Decision**: 加解密收口在 Jpa 注册表（唯一读写通道，list/find/save 全经 toDef/save）——上游（ProviderService、EmailNotifyAdapter、controllers）拿到的永远是明文 Def，全链零改动；这是 021「环境读取不改契约」纪律在存储层的复用。掩码补缺在 web 展示层，与加密正交（加密防 db 外流，掩码防管理台回显）。

## 关键设计裁决（详见 research.md）

| # | 裁决 | 要点 |
|---|------|------|
| R1 | AES-256-GCM + JDK 内置 | 认证加密防篡改；零新依赖；`enc:v1:` + base64(iv‖ct‖tag) |
| R2 | 收口在 Jpa 注册表 | Registry 契约零改动；上游全链无感知 |
| R3 | 主密钥两档解析 | 环境变量优先；文件档首启生成 0600；格式校验拒启 |
| R4 | 幂等迁移 + 密钥守卫 | 前缀判别明文补密；全部密文解不开=密钥不匹配拒启，部分坏=单行 WARN |
| R5 | 敏感字段名录 | config 内 password/token/secret/api_key/access_key（与 021 Redactor 名录对齐）；webhook url 整条视为地址不加密（边界如实） |
| R6 | 通知渠道掩码 | 复刻 ProviderView 确定性掩码 + 幂等未修改判定，同一口径 |
| R7 | 不做的 | keygen 子命令（openssl 一行够用）、密钥轮换、真实 KMS、url 加密 |
