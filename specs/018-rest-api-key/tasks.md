# Tasks: REST API Key 认证

**Input**: Design documents from `/specs/018-rest-api-key/`

**Prerequisites**: plan.md、spec.md、research.md（R1~R9）、data-model.md、contracts/auth-contract.md、quickstart.md

**Tests**: 包含测试任务——项目质量门禁要求核心逻辑有单测覆盖（宪法「开发流程与质量门禁」），且 018 是安全特性，路径裁决表必须测试全覆盖。

**Organization**: 按用户故事分组；US1 为 MVP（锁门最小闭环），US2/US3 依次叠加。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1/US2/US3）

## Path Conventions

Maven 多模块单体，涉及 oryxos-storage / oryxos-web / oryxos-cli / oryxos-boot 四个既有模块（见 plan.md「Source Code」）。

---

## Phase 1: Setup

**Purpose**: 表结构先行——所有后续任务的存储前提

- [X] T001 在 oryxos-storage/src/main/resources/schema.sql 末尾追加 `api_keys` 表（DDL 见 data-model.md；`CREATE TABLE IF NOT EXISTS`，`key_hash` UNIQUE 自带隐式索引无需显式建索引，注释注明 018、新表非 ALTER 无迁移风险，镜像 `web_users` 段落的注释风格）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 凭证实体与校验服务——三个故事共同依赖的核心

**⚠️ CRITICAL**: 本阶段完成前不得开始任何用户故事

- [X] T002 [P] 新建 JPA 实体 oryxos-storage/src/main/java/io/oryxos/storage/ApiKey.java（字段映射见 data-model.md；镜像 WebUser.java 的注解与 SuppressFBWarnings 风格）
- [X] T003 [P] 新建 oryxos-storage/src/main/java/io/oryxos/storage/ApiKeyRepository.java（Spring Data JPA：`findByKeyHash`、`findByName`、`existsByName`、`findAll`）
- [X] T004 新建 oryxos-storage/src/main/java/io/oryxos/storage/ApiKeyService.java（依赖 T002/T003）：`create(name)` 生成 `oryx_`+42 位 base62 明文（SecureRandom）→ 落 SHA-256 hex 与前缀 → 返明文仅此一次，重名抛清晰异常；`verify(plaintext)` 先 SHA-256 再 `findByKeyHash` + `MessageDigest.isEqual` 复核 + `revoked_at` 判定，通过后 60s 内存节流更新 `last_used_at`（更新失败仅日志不抛，FR-009/R6）；`revoke(name)` 写 `revoked_at`（幂等）；`list()`；`hasActiveKey()`。全程日志只记前缀（R1/R2/R6）
- [X] T005 [P] 新建单测 oryxos-storage/src/test/java/io/oryxos/storage/ApiKeyServiceTest.java：明文格式（前缀/长度/字符集）、库中无明文只有 64 位 hex、verify 对/错/不存在/已吊销四路、revoke 即时生效与幂等、重名拒绝、last_used 节流（60s 内不重写）
- [X] T006 [P] 新建 oryxos-web/src/main/java/io/oryxos/web/config/WebApiKeyProperties.java（prefix `oryxos.web.apikey`，仅 `enabled` 默认 false）并在 WebAuthConfig.java（或镜像其注册方式）完成配置绑定注册

**Checkpoint**: `ApiKeyService` 单测全绿——故事实现可以开始

---

## Phase 3: User Story 1 - 把 REST 大门锁上 (Priority: P1) 🎯 MVP

**Goal**: CLI 生成 Key（明文一次）→ 开 flag → 无 Key/错 Key 401、对 Key 200；flag 默认关回归零破坏

**Independent Test**: quickstart V1~V3——默认配置 `/api/v1/profiles` 无凭据 200；`apikey add` 显示明文一次且库中只有哈希；开 flag 后 401/200 按契约裁决

- [X] T007 [US1] 新建 oryxos-web/src/main/java/io/oryxos/web/security/ApiKeyAuthFilter.java（依赖 T004/T006）：`enabled=false` 直接放行；解析 `Authorization: Bearer` 与 `X-API-Key` 双写法（FR-003）；`ApiKeyService.verify` 通过放行；失败统一 401——`ApiResponse.error(401,"Unauthorized")` + `WWW-Authenticate: Bearer realm="OryxOS"`，所有失败原因同一响应（FR-004/R9）；filter 内不抛异常直接写响应（镜像 BasicAuthFilter 的 javadoc 说明与 SuppressFBWarnings 风格）
- [X] T008 [US1] 新建 oryxos-web/src/main/java/io/oryxos/web/config/ApiKeyFilterConfig.java：`FilterRegistrationBean<ApiKeyAuthFilter>` + `addUrlPatterns("/api/v1/*")`（镜像 AuthFilterConfig.java；javadoc 注明与 012 的 `/admin/*` 模式互不重叠）
- [X] T009 [US1] 新建 oryxos-cli/src/main/java/io/oryxos/cli/command/ApiKeyCommand.java：`@Command(name="apikey")` 骨架 + `add` 子命令（镜像 UserCommand 的 withService 一次性 Spring 上下文模式；成功输出明文 + 「This is the ONLY time…」警告，契约见 contracts/auth-contract.md §2）
- [X] T010 [US1] 在 oryxos-cli/src/main/java/io/oryxos/cli/OryxOsCli.java 的 subcommands 注册 ApiKeyCommand.class（依赖 T009）
- [X] T011 [P] [US1] 新建 oryxos-web/src/test/java/io/oryxos/web/security/ApiKeyAuthFilterTest.java（依赖 T007/T008）：flag 关放行、无 Key 401、错 Key 401、对 Key 放行、Bearer 与 X-API-Key 等效、401 响应体与挑战头断言、错误响应不可区分（无 Key vs 错 Key vs 已吊销同体）

**Checkpoint**: quickstart V1~V3 可走通——MVP 可交付

---

## Phase 4: User Story 2 - Key 生命周期管理 (Priority: P2)

**Goal**: 多 Key 并存互不干扰；`list` 盘点无明文；`revoke` 即时生效

**Independent Test**: quickstart V4——两把 Key 并存、吊销一把另一把不受影响、list 输出 NAME/PREFIX/STATUS/CREATED_AT/LAST_USED_AT

- [X] T012 [US2] 在 oryxos-cli/src/main/java/io/oryxos/cli/command/ApiKeyCommand.java 增加 `list` 子命令（表格输出契约见 contracts/auth-contract.md §2：无明文、空表提示 `Run 'oryxos apikey add <name>'…`）与 `revoke` 子命令（成功提示、不存在报错非零退出码、重复吊销幂等提示）
- [X] T013 [P] [US2] 新建 CLI 侧行为单测 oryxos-cli/src/test/java/io/oryxos/cli/command/ApiKeyCommandTest.java（如既有 CLI 测试模式不适用则并入 T005 的 service 层断言并在本任务注明）：list 输出含前缀无明文、revoke 不存在名称的错误路径。**注明**：条件触发——CLI 子命令自起完整 Spring 上下文（镜像 UserCommand，其同样无独立单测），断言已并入 T005（list 无明文/revoke not found），CLI 面行为由真机走查 V2/V4 覆盖（见 acceptance-report.md）
- [X] T014 [US2] 多 Key 并存集成断言：在 oryxos-web/src/test/java/io/oryxos/web/security/ApiKeyAuthFilterTest.java 追加——两把 Key 各自可过、吊销 A 后 A 401 且 B 仍 200（SC-004）

**Checkpoint**: quickstart V4 可走通——US1+US2 独立可测

---

## Phase 5: User Story 3 - 与既有体系无冲突共存 (Priority: P3)

**Goal**: health/auth 子树/OPTIONS 豁免；管理台 session 互认；两条启动 WARN；`/admin/**` 零影响

**Independent Test**: quickstart V5~V6——探活无凭据 200、登录后管理台数据页正常、异常 flag 组合启动成功且 WARN 在日志

- [X] T015 [US3] 在 oryxos-web/src/main/java/io/oryxos/web/security/ApiKeyAuthFilter.java 增加豁免判定：HTTP `OPTIONS`、`/api/v1/health`、`/api/v1/auth/` 前缀直接放行（FR-002/FR-014，裁决表见 contracts/auth-contract.md §1）
- [X] T016 [US3] 管理台 session 互认（FR-011/R4）：将 oryxos-web/src/main/java/io/oryxos/web/security/BasicAuthFilter.java 的 `SESSION_COOKIE` 常量提升为共享（public 或抽至 security 包内常量类，012 其余逻辑零改动），ApiKeyAuthFilter 在 Key 缺失/无效时读 `oryxos_session` cookie → `WebSessionService.findValid` 有效即放行
- [X] T017 [P] [US3] 新建 oryxos-web/src/main/java/io/oryxos/web/security/ApiKeyStartupCheck.java（镜像 AuthStartupCheck：`ApplicationRunner` + `@ConditionalOnWebApplication(SERVLET)`）：`enabled=true` 且 `!hasActiveKey()` → WARN 提示 `oryxos apikey add`；`enabled=true` 且 `oryxos.web.auth.enabled=false` → WARN 管理台数据页将不可用；均不抛异常（FR-012/R7，javadoc 说明与 012 fail-fast 的差异理由）。同任务新建配套单测 oryxos-web/src/test/java/io/oryxos/web/security/ApiKeyStartupCheckTest.java：两分支各断言 WARN 产生且 run() 不抛、正常配置无 WARN
- [X] T018 [P] [US3] 在 oryxos-web/src/test/java/io/oryxos/web/security/ApiKeyAuthFilterTest.java 追加豁免与互认用例：OPTIONS 放行、health 放行、auth 子树放行、有效 session 无 Key 放行、无效 session 无 Key 401
- [X] T019 [US3] 新建端到端测试 oryxos-boot/src/test/java/io/oryxos/boot/ApiKeyAuthE2ETest.java（镜像 AuditDashboardE2ETest 模式，依赖 T007~T017）：双 flag 全开下走 quickstart V3~V5 主路径——add → 401/200 → revoke 即时生效 → health 豁免 → session 互认

**Checkpoint**: 全部故事独立可测；`/admin/**` 与 `/api/v1/auth/*` 行为与 012 验收一致

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 配置样例、文档与全量质量门禁

- [X] T020 [P] 在 config/application.yml.example 追加 `oryxos.web.apikey.enabled` 配置段与注释（默认 false、开启前先 `oryxos apikey add` 的提示）
- [X] T021 [P] 更新 docs/CliGuide.md：新增 `oryxos apikey add/list/revoke` 三命令说明（对齐 contracts/auth-contract.md §2 的输出契约）
- [X] T022 [P] 更新 website 文档（website/ 与 website/zh/ 下涉及 REST API 认证说明的页面，如无对应页面则在 docs/ 补一段部署说明）：如何开启 API Key 门禁、调用方接入方式、与管理台认证的关系
- [X] T023 按 quickstart.md 完整走查 V1~V6 并记录结果到 specs/018-rest-api-key/acceptance-report.md（镜像 016 的 acceptance-report 形式，SC-001~SC-007 逐项对勾）
- [X] T024 运行 `mvn verify` 全量质量门禁（Spotless + P3C + Checkstyle + SpotBugs/FindSecBugs + OWASP）并清零新增告警；确认无明文 Key 进入任何日志语句（SC-005 复查）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1（T001）**: 无依赖，立即可做
- **Phase 2（T002~T006）**: 依赖 T001；T002/T003/T006 可并行，T004 依赖 T002/T003，T005 依赖 T004
- **Phase 3（US1）**: 依赖 Phase 2；T007 依赖 T004/T006
- **Phase 4（US2）**: 依赖 Phase 2（service 的 revoke/list 已在 T004）；T012 依赖 T009 的命令骨架 → 实际顺序在 US1 之后
- **Phase 5（US3）**: T015/T016 改 T007 产出的同一文件 → 必须在 US1 之后串行
- **Phase 6**: 依赖全部故事完成

### User Story Dependencies

- **US1 (P1)**: 仅依赖 Foundational——MVP
- **US2 (P2)**: 依赖 US1 的 T009（同文件 ApiKeyCommand.java 加子命令）；filter 侧无依赖
- **US3 (P3)**: 依赖 US1 的 T007（同文件 ApiKeyAuthFilter.java 加豁免/互认）

### Parallel Opportunities

- Phase 2：T002 ∥ T003 ∥ T006（三个不同文件）
- Phase 3：T011 与 T009/T010 并行（web 测试 vs CLI）
- Phase 5：T017 与 T015/T016 并行（新文件 vs 改 filter）
- Phase 6：T020 ∥ T021 ∥ T022（三处文档互不相干）

---

## Parallel Example: Phase 2

```bash
# 三个互不依赖的新文件同时开工：
Task: "T002 新建 ApiKey.java 实体"
Task: "T003 新建 ApiKeyRepository.java"
Task: "T006 新建 WebApiKeyProperties.java 并注册"
# 然后串行：T004 ApiKeyService →（并行）T005 单测
```

---

## Implementation Strategy

### MVP First（US1 Only）

1. Phase 1 + Phase 2（T001~T006）：表 + 服务 + 配置就绪
2. Phase 3（T007~T011）：锁门闭环
3. **STOP and VALIDATE**: quickstart V1~V3 走通即可演示（生成 Key → 401/200）
4. US2/US3 依次叠加，各自 checkpoint 可独立验收

### 注意

- T007→T015/T016 与 T009→T012 是同文件递进，禁止并行
- 每完成一个 Phase 提交一次（commit message 遵循项目规约，scope 用 `auth` 或 `web`/`cli`/`storage` 按主要触点）
- 安全红线贯穿所有任务：明文 Key 只允许出现在 `apikey add` 的 stdout；测试断言中构造的 Key 除外
