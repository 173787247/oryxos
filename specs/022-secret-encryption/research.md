# Research: 密钥加密存储

**Feature**: 022-secret-encryption | **Date**: 2026-09-01

## R1 — 加密算法与密文形态：AES-256-GCM + JDK 内置，`enc:v1:` 前缀

**Decision**: `javax.crypto`（JDK 内置）AES-256-GCM，每次加密生成随机 12 字节 IV，密文形态 `enc:v1:` + Base64(IV ‖ ciphertext ‖ GCM tag)。

**Rationale**:
- GCM 是认证加密——密文被篡改/截断时解密即抛异常，天然满足边界场景「密文行被手工改坏→单条清晰报错」，无需额外校验和
- JDK 21 内置实现成熟且硬件加速（AES-NI），零新依赖（OWASP 门禁无新扫描面）
- `enc:v1:` 前缀承担双职：明文/密文判别（迁移幂等的依据，FR-003/FR-005）+ 未来 `v2`（换算法/接 KMS）识别位
- 随机 IV 使同一明文两次加密产生不同密文——不可做相等性判定，但凭证列无此需求（providers 按 name 主键查）

**Alternatives considered**: BouncyCastle（引新依赖无必要）；AES-CBC+HMAC（两步拼装易错，GCM 一步到位）；Jasypt（引依赖 + 默认 PBE 弱于直接 AES-GCM）。

## R2 — 加解密收口点：Jpa 注册表内部，Registry 契约零改动

**Decision**: 加密在 `JpaProviderRegistry.save` / `JpaNotifyChannelRegistry.save`（写库前），解密在两者的 `toDef`（读出时）。core 的 `ProviderRegistry` / `NotifyChannelRegistry` 接口与 `ProviderDef` / `NotifyChannelDef` 值对象零改动。

**Rationale**:
- 摸底确认两注册表是唯一读写通道（list/find/save 全经 toDef/save 互转），是天然咽喉；上游 `ProviderService`（建 ChatModel）、`EmailNotifyAdapter`（读 password）、controllers 拿到的永远是明文 Def，**全链零改动**
- 021「trace 走环境读取、Auditor 接口不动」的同款纪律：横切关切在实现层收口，不污染契约
- YAML 播种路径（`ProviderRegistryBootstrap`）经 `registry.save` 入库 → 播种值自动加密（US1 场景 4 免费达成）

**Alternatives considered**: JPA AttributeConverter（对 providers.api_key 可行，但 notify config 是 JSON 内**逐项**加密，Converter 粒度不匹配；两处口径不一致不如都收口在注册表）；controller 层加密（漏 YAML 播种路径，且解密点分散）。

## R3 — 主密钥两档解析：环境变量优先 → 工作区文件自动生成

**Decision**: `MasterKeyResolver`：`ORYXOS_MASTER_KEY` 环境变量（Base64 的 32 字节）存在则用之；否则读 `{oryxos.root}/master.key`，不存在则生成随机 32 字节写入（POSIX 权限 0600）。密钥格式非法（长度/编码）→ 启动即抛清晰异常。

**Rationale**:
- 两档口径为特性启动前维护者已裁决（n8n `N8N_ENCRYPTION_KEY` 同款验证路径）：本地试用零配置零感知，生产一条环境变量（与 `${DEEPSEEK_API_KEY}` 注入同构）
- `oryxos.root` 经 `OryxOsRuntime` 的 `@Value("${oryxos.root:.oryxos}")` 已有解析（集成测试可指临时目录）；`.oryxos/` 整体在 `.gitignore`（第 53 行）——master.key 免费获得版本库豁免（FR-009）
- 环境变量与文件同时存在且不一致：环境变量优先、文件忽略；解不开既有密文时按「密钥不匹配」拒启（不自动回退另一档——静默换钥匙是排障噩梦）

**Alternatives considered**: 仅环境变量（逼死本地试用）；仅文件（K8s Secret 分发不便，且钥匙恒与柜子同目录）；Spring 配置项 `oryxos.master-key`（会诱导写进 application.yml 明文提交，环境变量语义更强制）。

## R4 — 存量迁移与密钥守卫：启动一次幂等扫描

**Decision**: `SecretMigration`（storage，cli 装配后启动执行，`AuditSchemaUpgrade` 同位）：扫描 providers 与 notify_channels 全表——明文敏感值（无 `enc:v1:` 前缀且非空）→ 加密回写并计数（日志「已加密 N 条凭证」）；密文值 → 试解密做守卫：**全部密文都解不开 = 密钥不匹配 → 抛异常拒启**（报错含两条恢复路径：找回原密钥 / 管理台删除重录）；**部分解不开 = 单行损坏 → WARN 定位条目继续启动**（FR-010）。

**Rationale**:
- 前缀判别使迁移天然幂等：中断续跑、重复启动都只处理残余明文行
- 「全坏=钥匙错、部分坏=行损坏」的区分让两种故障各得其所：前者是部署事故必须拦（SC-004），后者是数据事故不应放大为全站不可用
- 凭证行数十以内，全表扫描毫秒级（SC-006 无忧）

**Alternatives considered**: 懒迁移（读时遇明文才加密——明文在库中滞留期不可控，SC-001 无法一次达成）；独立迁移命令（多一个必须记得跑的步骤，违背零配置）。

## R5 — 敏感字段名录：与 021 Redactor 对齐；webhook url 边界如实

**Decision**: notify config 内加密名录 = `password` / `passwd` / `secret` / `token` / `api_key` / `apikey` / `access_key`（大小写不敏感），与 021 `Redactor.SENSITIVE_FIELD` 名录同源；名录外（host/port/from/to/username/subject/encryption）保持明文。webhook 类渠道的 `url`（含飞书/企微/钉钉机器人地址内嵌 token）**本版本不加密不掩码**。

**Rationale**:
- 名录与脱敏层同源，产品语言统一：「什么算敏感」全系统一个答案
- username 是身份标识非秘密；host/port 加密只会伤排障
- webhook url 定位是「地址」：管理台需完整可见可编辑（用户校对粘贴的 url），且其风险面（可冒发消息）远小于 API key（可烧钱调模型）；如实记为边界而非装作已防护——若未来升级，`enc:v1:` 前缀与 SecretCipher 已留好位

**Alternatives considered**: url 一并加密（列表页/编辑页需完整回显才可用，加密后掩码即破坏既有交互，收益与代价不成比）；可配置名录（YAGNI，021 已裁决脱敏规则内置不可配，同款）。

## R6 — 通知渠道掩码回显：复刻 ProviderView 机制

**Decision**: `NotifyChannelView` 对 config 敏感项（R5 名录）输出确定性掩码（`****` + 末 4 位，复用 `ProviderView.mask` 口径）；`NotifyChannelApiController` 更新路径：敏感项提交值等于原值的掩码或为空 → 保持原值，否则按新值加密落库。

**Rationale**:
- ProviderView 的「确定性掩码 + mask(mask(k))==mask(k) 幂等 + 掩码原样提交=未修改」机制已在生产验证（018 时代产物），复刻而非另造
- 摸底确认这是真实缺口：ProviderView 已掩码，`NotifyChannelView.from` 却整包回显 config（含 SMTP password 明文）——US3 的靶心
- 掩码口径沿用 Provider 的「末 4 位」而非 021 Redactor 的「前 4 位」：同为回显掩码语义（且未修改判定依赖既有实现），spec Assumptions 已声明沿用既有口径

**Alternatives considered**: 敏感项完全不回显（编辑表单无法区分「已配置」与「未配置」，UX 退化）；每次编辑强制重输（对多字段 config 过重）。

## R7 — 不做的（边界收口）

- **`oryxos keygen` 子命令**：不做。生产文档给 `openssl rand -base64 32` 一行；文件档自动生成已覆盖本地场景（YAGNI）
- **`oryxos secrets reset` 命令**：不做。恢复路径 = 管理台/API 既有 CRUD 删除重录（报错文案指向它）
- **密钥轮换**：不做 UI/命令；`enc:v1:` 版本位是未来轮换的识别基础，本版本仅预留
- **真实 KMS/Vault 对接**：不做；`SecretCipher` 接口即升级缝
- **审计表内容加密**：021 已裁决（展示层脱敏 + 落库原文），FR-011 钉死
