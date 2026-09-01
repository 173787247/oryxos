# Tasks: 密钥加密存储

**Input**: Design documents from `/specs/022-secret-encryption/`

**Prerequisites**: plan.md、spec.md、research.md（R1~R7）、data-model.md、contracts/secret-storage.md、quickstart.md

**Tests**: 包含测试任务——质量门禁要求核心逻辑单测覆盖（宪法「开发流程与质量门禁」），且加密往返（SC-001/SC-002）、故障自明（SC-004）、掩码不回显（SC-005）必须测试钉死。

**Organization**: 按用户故事分组；SecretCipher/MasterKeyResolver 在 Foundational 一次成型——US1（落库加密+迁移）为 MVP，US2（两档配置+故障自明）、US3（管理台掩码补缺）依次叠加。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1/US2/US3）

## Path Conventions

Maven 多模块单体，涉及 oryxos-core / oryxos-storage / oryxos-web / oryxos-cli / oryxos-boot 五个既有模块（见 plan.md「Source Code」）。零新表、零新列、零新依赖。

---

## Phase 1: Foundational (Blocking Prerequisites)

**Purpose**: 加密原语与主密钥解析——三个故事共同依赖的核心

**⚠️ CRITICAL**: 本阶段完成前不得开始任何用户故事

- [X] T001 [P] 新建 oryxos-core/src/main/java/io/oryxos/core/secret/SecretCipher.java（契约见 data-model.md）：`String encrypt(String)`（null/空原样）、`String decrypt(String)`（无 `enc:v1:` 前缀视为明文原样返回，解密失败抛 SecretDecryptException）、`boolean isEncrypted(String)`；同包新建 SecretDecryptException.java 与 SensitiveConfigKeys.java（名录常量 `password/passwd/secret/token/api_key/apikey/access_key` 大小写不敏感判定，javadoc 注明与 021 Redactor 名录同源——storage 加密与 web 掩码共用）
- [X] T002 [P] 新建 oryxos-core/src/main/java/io/oryxos/core/secret/MasterKeyResolver.java（决策树见 data-model.md）：`ORYXOS_MASTER_KEY` 环境变量优先（Base64 解码校验 32 字节，非法即抛含格式要求与 `openssl rand -base64 32` 提示的异常）→ 缺省读 `{oryxosRoot}/master.key`，不存在则生成随机 32 字节写入并置 POSIX 0600；环境读取经可注入 supplier（构造参数默认 `System::getenv`）保证单测可控；密钥值不得进任何日志
- [X] T003 新建 oryxos-core/src/main/java/io/oryxos/core/secret/LocalMasterKeyCipher.java（依赖 T001/T002）：AES-256-GCM——encrypt 每次随机 12 字节 IV，输出 `enc:v1:` + Base64(IV‖ct‖tag)；decrypt 剥前缀解码解密，GCM 校验失败/格式坏抛 SecretDecryptException（异常信息不含明文与密钥）
- [X] T004 [P] 新建单测 oryxos-core/src/test/java/io/oryxos/core/secret/LocalMasterKeyCipherTest.java（依赖 T003）：往返一致、同明文两次加密密文不同（随机 IV）、isEncrypted 前缀判别、密文篡改/截断抛 SecretDecryptException、null/空串原样、无前缀明文 decrypt 原样返回（迁移期兼容）、**解密失败的异常 message 不含密钥值与明文片段**（FR-009 显式断言）
- [X] T005 [P] 新建单测 oryxos-core/src/test/java/io/oryxos/core/secret/MasterKeyResolverTest.java（依赖 T002，@TempDir 指工作区）：无环境变量首启自动生成且权限 0600、二次启动读同一密钥、环境变量优先于文件（注入 supplier）、环境变量非法格式抛错含格式提示、文件内容损坏抛错、**全部异常 message 不含密钥 Base64 值**（FR-009 显式断言）

**Checkpoint**: Cipher/Resolver 单测全绿——存储收口可以开始

---

## Phase 2: User Story 1 - 数据库泄露不再等于凭证泄露 (Priority: P1) 🎯 MVP

**Goal**: 凭证经任何写入路径落库即密文、使用即明文；存量明文一次启动幂等迁移；功能回归零破坏

**Independent Test**: quickstart V2/V3/V4——录入后 sqlite 直查只见 `enc:v1:`；LLM 调用/通知发送行为不变；手工改回明文重启即被迁移且幂等

- [X] T006 [US1] 修改 oryxos-storage/src/main/java/io/oryxos/storage/JpaProviderRegistry.java（依赖 T003）：构造注入 SecretCipher；save 时 `entity.setApiKey(cipher.encrypt(...))`、toDef 时 decrypt——单条解密失败捕获 SecretDecryptException 记 WARN（含 provider 名）并以 null apiKey 返回，不拖垮 list（FR-010）；**Registry 接口与 ProviderDef 零改动**（R2 红线）；检查既有直构点是否需要兼容旧构造（保留旧构造委托明文直通 NOOP cipher 以保全既有测试）
- [X] T007 [US1] 修改 oryxos-storage/src/main/java/io/oryxos/storage/JpaNotifyChannelRegistry.java（依赖 T003）：构造注入 SecretCipher；writeConfig 前对 SensitiveConfigKeys 命中的项逐值 encrypt、readConfig 后逐值 decrypt（单项失败 WARN 定位渠道+字段、该项以 null 值返回）；名录外项原样；旧构造兼容口径同 T006
- [X] T008 [US1] 新建 oryxos-storage/src/main/java/io/oryxos/storage/SecretMigration.java（依赖 T006/T007）：启动扫描 providers 与 notify_channels——明文敏感值（非空且 !isEncrypted）经注册表 save 路径加密回写并计数，日志「已加密 N 条凭证」；密文值试解密（守卫细化在 T012，本任务先落「记录解密失败清单」骨架）；幂等（前缀判别，重复启动/中断续跑安全）
- [X] T009 [US1] 修改 oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java（依赖 T008）：`@Bean SecretCipher`（MasterKeyResolver 用 `oryxosRoot()`）；两注册表 @Bean（L177/L848）注入 cipher；SecretMigration 在数据源就绪后启动执行（AuditSchemaUpgrade 同位）
- [X] T010 [P] [US1] 新建 oryxos-storage/src/test/java/io/oryxos/storage/SecretStorageTest.java（依赖 T006~T008，@DataJpaTest 走手工 schema.sql，镜像 LlmCallRepositoryTest 配置）：save 后库中列值为 `enc:v1:` 密文且 find 读出明文、notify config 仅敏感项加密普通项原样、预置明文行跑 SecretMigration 后变密文且日志计数、二次运行零迁移（幂等）、预置坏密文行 list 照常返回且该行凭证为 null
- [X] T011 [US1] 新建 oryxos-boot/src/test/java/io/oryxos/boot/SecretEncryptionE2ETest.java（依赖 T009；镜像 TraceE2ETest 的 mock provider + 临时工作区模式）：经 API 创建 Provider 与含 password 的 email 渠道 → 直查 SQLite 断言两处均为密文、无明文（SC-001）；mock 对话全链照常（SC-002 功能回归）；断言渠道查询接口此阶段仍返回明文 config（US3 前现状锚点，T017 收口为掩码）；手工 UPDATE 回明文重启上下文→ 迁移生效

**Checkpoint**: quickstart V2/V3/V4 可走通——MVP 可交付（db 文件外流不再泄凭证）

---

## Phase 3: User Story 2 - 主密钥两档配置与故障自明 (Priority: P2)

**Goal**: 零配置首启静默可用；环境变量档优先；密钥缺失/不匹配/非法 100% 启动拦截并指路恢复

**Independent Test**: quickstart V1/V5/V6——全新工作区免配置启动；删钥匙拒启且报错含恢复路径；环境变量优先与非法格式报错

- [X] T012 [US2] 修改 oryxos-storage/src/main/java/io/oryxos/storage/SecretMigration.java（依赖 T008，同文件串行）：守卫定案——存在密文且**全部**解密失败 → 抛 IllegalStateException 拒启，文案「已有 N 条加密凭证无法解密。可能：主密钥丢失或被更换。恢复：找回原密钥；或经管理台删除并重新录入凭证」；**部分**失败 → 逐条 WARN（渠道/Provider 名 + 字段）继续启动（FR-010）；全新库（零凭证行）直接通过。**拒启断言落 storage 层**：oryxos-storage/src/test/java/io/oryxos/storage/SecretStorageTest.java 追加——预置密文行 + 换错钥 cipher 直调 SecretMigration → 断言抛 IllegalStateException 且文案含「无法解密」与两条恢复路径；部分坏行场景断言 WARN 不抛（@SpringBootTest 单上下文测不了启动失败，直调是可行且更精准的口径，SC-004）
- [X] T013 [P] [US2] 在 oryxos-boot/src/test/java/io/oryxos/boot/SecretEncryptionE2ETest.java 追加（依赖 T011/T012，同文件串行）：全新工作区零配置启动成功、无需任何密钥配置（SC-003）；master.key 已自动生成且权限 POSIX 0600、内容为合法 Base64 32 字节；密钥错误拒启由 T012 的 storage 层直调断言覆盖，真机拒启走 quickstart V5 走查；环境变量档与非法格式场景由 MasterKeyResolverTest 覆盖（E2E 进程内环境变量不可控，走 supplier 注入单测口径）

**Checkpoint**: quickstart V1/V5/V6 可走通——US1+US2 独立可测

---

## Phase 4: User Story 3 - 管理台与 API 不再回显凭证明文 (Priority: P3)

**Goal**: 通知渠道敏感项掩码回显 + 掩码原样/留空=保持原值；全接口无明文

**Independent Test**: quickstart V7——列表/详情接口敏感项为掩码；编辑不动密码保存后通知照常；新值保存生效

- [ ] T014 [US3] 修改 oryxos-web/src/main/java/io/oryxos/web/controller/dto/NotifyChannelView.java：from 时对 config 中 SensitiveConfigKeys 命中项以 `ProviderView.mask`（`****`+末 4 位、幂等）替换值；新增静态 `maskConfig(Map)` 供 controller 未修改判定复用；javadoc 注明与 ProviderView 同口径
- [ ] T015 [US3] 修改 oryxos-web/src/main/java/io/oryxos/web/controller/NotifyChannelApiController.java（依赖 T014）：更新路径对敏感项做未修改判定——提交值为空或等于原值掩码 → 沿用原值；否则取新值（加密由注册表收口，controller 不碰密文）；创建路径不变
- [ ] T016 [P] [US3] 修改 oryxos-web/src/test/java/io/oryxos/web/controller/NotifyChannelApiControllerTest.java（依赖 T015）：列表/详情响应 password 为掩码且无明文、掩码原样提交后原值保留（mock registry 收到原 password）、新值提交生效、非敏感项（host/from）原样回显、Provider 掩码回归不受影响
- [ ] T017 [US3] 在 oryxos-boot/src/test/java/io/oryxos/boot/SecretEncryptionE2ETest.java 追加（依赖 T013/T015，同文件串行）：`GET /api/v1/notify-channels` 全响应断言不含明文密码、掩码在位（SC-005）；掩码原样 PUT 后库中密文不变（未修改判定全链）；新密码 PUT 后读出为新值

**Checkpoint**: 全部故事独立可测

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: 文档、口径收回与全量验收

- [ ] T018 [P] 文档同步：website/zh/docs/api.md 与 website/docs/api.md 通知渠道节补「敏感项掩码回显与未修改判定」语义；docs/CliGuide.md（或部署章节）新增「主密钥」小节——两档配置、`openssl rand -base64 32`、恢复路径、威胁边界如实（防 db 单独外流；文件档不防整机沦陷，生产用环境变量档，SC-007）
- [ ] T019 [P] 口径收回：oryxos-storage/src/main/resources/schema.sql 的 providers 表注释（「api_key 明文落库…让步」）更新为「api_key 密文落库（022，enc:v1: 前缀，主密钥见部署文档）」；CLAUDE.md 配置加载规则小节补一句「落库凭证经主密钥加密（022）」
- [ ] T020 按 quickstart.md 完整走查 V1~V7（真机 fat JAR + sqlite 直查 + 管理台浏览器复用缓存 Chromium）并记录到 specs/022-secret-encryption/acceptance-report.md（SC-001~SC-007 逐项对勾，镜像 018~021 报告形式）
- [ ] T021 运行 `mvn verify` 全量质量门禁并清零新增告警（Spotless + P3C + Checkstyle + SpotBugs/FindSecBugs + OWASP——重点关注 crypto 相关 FindSecBugs 规则：STATIC_IV 等必须以「每次随机 IV」实现自然通过，不得靠 Suppress）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1（T001~T005）**: T001 ∥ T002 先行；T003 依赖 T001/T002；T004/T005 随后
- **Phase 2（US1）**: 依赖 Phase 1；T006 ∥ T007 → T008 → T009；T010 ∥ T011 收尾
- **Phase 3（US2）**: T012 依赖 T008；T013 依赖 T011/T012
- **Phase 4（US3）**: T014 → T015 → T016；T017 依赖 T013/T015
- **Phase 5**: 依赖全部故事完成

### 同文件递进链（禁止并行）

- `SecretMigration.java`: T008 → T012
- `SecretStorageTest.java`: T010 → T012 追加（拒启断言）
- `SecretEncryptionE2ETest.java`: T011 → T013 → T017
- `NotifyChannelApiControllerTest.java`: 既有 → T016 追加

### Parallel Opportunities

- Phase 1：T001 ∥ T002；T004 ∥ T005
- Phase 2：T006 ∥ T007（不同文件）；T010 ∥ T011
- Phase 4：T016 与 T017 前半可并行推进（不同文件）
- Phase 5：T018 ∥ T019

---

## Parallel Example: Phase 1

```bash
Task: "T001 SecretCipher 契约 + 名录常量"   ∥   Task: "T002 MasterKeyResolver 两档解析"
# 然后：T003 LocalMasterKeyCipher → T004 ∥ T005 单测
```

---

## Implementation Strategy

### MVP First（US1 Only）

1. Phase 1（T001~T005）：加密原语与密钥解析就位
2. Phase 2（T006~T011）：落库加密 + 迁移闭环
3. **STOP and VALIDATE**: quickstart V2/V3/V4 走通即可演示（录入 → sqlite 只见密文 → 功能照常）
4. US2/US3 依次叠加，各自 checkpoint 独立验收

### 注意

- **Registry 契约零改动是红线**（R2）：接口与 Def 值对象不动，加解密收口在 Jpa 实现内部；旧构造委托 NOOP 直通保全既有测试交互契约（021 教训制度化）
- **密钥不进日志**（FR-009）：Resolver/Cipher 的所有异常信息只描述问题不含密钥值与明文
- **GCM 随机 IV 是 FindSecBugs 通行证**：STATIC_IV 类告警必须靠正确实现消除，不得 Suppress
- 每完成一个 Phase 提交一次（scope 按主要触点：core/storage/web）
