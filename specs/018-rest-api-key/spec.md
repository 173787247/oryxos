# Feature Specification: REST API Key 认证

**Feature Branch**: `018-rest-api-key`

**Created**: 2026-08-26

**Status**: Draft

**Input**: User description: "REST API Key 认证（018-rest-api-key）：为 /api/v1/** REST 端点加上 API Key 机器调用认证，补齐 v0.2「把门锁上」缺角（012-web-auth 已明确 REST API Key 为后续独立 PR）。要点：feature flag 默认关，回归零破坏；Key 由管理员通过 CLI（oryxos apikey 子命令）生成/吊销，SQLite 存哈希不存明文；调用方通过请求头携带 Key；开启后未带 Key 或 Key 无效的 /api/v1/** 调用返回 401；/api/v1/health 保持免认证（探活）；/api/v1/auth/* 管理台登录子树维持现状；管理台 /admin/** 的 012 session/Basic Auth 体系不受影响；RBAC/多租户不做（留到 v1.0）。"

## Clarifications

### Session 2026-08-26

- Q: API Key 认证开启后，管理台 SPA 调 `/api/v1/**` 数据接口如何放行？ → A: session 即凭据——带有效管理台 session 的请求视同通过认证，API Key 与 session 任一有效即放行（不给 SPA 发内置 Key，不新增密钥暴露面）。
- Q: apikey 开启但管理台认证关闭的组合，启动时怎么处理？ → A: 告警不阻断——启动成功并输出「管理台数据页面将不可用」清晰警告；纯机器调用部署是合法场景，不 fail-fast。
- Q: API Key 是否支持自动过期？ → A: 不做过期——本期只做手动吊销，不加 `expires_at` 字段，留待真实企业需求出现后按需引入。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 把 REST 大门锁上（Priority: P1）

运维管理员不想让 REST API 在内网裸奔。他用 CLI 生成一把 API Key（明文只在生成那一刻显示一次），把 Key 交给调用方业务系统，改配置开启 API Key 认证并重启。此后：调用方在请求头带上 Key 就能正常调 `/api/v1/**`；不带 Key、带错 Key 的请求一律被拒（401）。不开启时，一切行为与现状完全一致。

**Why this priority**: 这是本 feature 存在的唯一理由——「API 裸奔过不了企业安全评审」是 v0.2 路线图明确要补的缺角，012-web-auth 已把管理台锁上，REST 机器调用是最后一扇没锁的门。锁门 + 发钥匙 + 验钥匙构成最小闭环，单独交付即有价值。

**Independent Test**: 默认配置启动 → `/api/v1/profiles` 无凭据可访问（现状回归）；跑 `oryxos apikey add ci-bot` → 终端显示一次明文 Key，数据库里只有哈希；开启认证并重启 → 无 Key 调 `/api/v1/profiles` 得 401，带对 Key 得 200，带错 Key 得 401。

**Acceptance Scenarios**:

1. **Given** 认证开关保持默认（关闭），**When** 无任何凭据调用任意 `/api/v1/**` 端点，**Then** 行为与本 feature 之前完全一致（回归零破坏）。
2. **Given** 管理员执行 `oryxos apikey add <name>`，**When** 命令成功，**Then** 终端显示完整明文 Key 一次并明确提示「仅显示这一次」，持久化存储中只有不可逆哈希，无明文。
3. **Given** 认证已开启且存在有效 Key，**When** 请求头携带该 Key 调用 `/api/v1/**`，**Then** 请求正常处理，与未开启认证时的响应一致。
4. **Given** 认证已开启，**When** 请求不带 Key、带格式错误的 Key 或带不存在的 Key，**Then** 返回 401 与统一错误响应体，不泄露「Key 是否存在/是否曾存在」等区分信息。
5. **Given** 认证已开启但系统中没有任何有效 Key，**When** 系统启动，**Then** 启动成功但输出清晰警告（提示先用 CLI 生成 Key），此时所有需认证请求返回 401。

---

### User Story 2 - Key 生命周期管理（Priority: P2）

管理员为每个调用方（CI 机器人、报表系统、第三方集成）各发一把 Key。某个调用方下线或 Key 疑似泄露时，管理员用 CLI 吊销那一把 Key，其余调用方不受影响；吊销立即生效。管理员随时能列出所有 Key 的名称、标识前缀、状态与最近使用时间，但永远看不到明文。

**Why this priority**: 只有一把永久钥匙的门等于换锁要通知所有人。按调用方发 Key、单独吊销、可盘点，是「锁上之后管得住」的最小治理配套；没有它，泄露一次就要全量换 Key。

**Independent Test**: 生成两把 Key（`ci-bot`、`report`）→ `oryxos apikey list` 显示两行（名称、前缀、创建时间、最近使用时间、状态，无明文）→ `oryxos apikey revoke ci-bot` → `ci-bot` 的 Key 下一次请求即 401，`report` 的 Key 仍正常。

**Acceptance Scenarios**:

1. **Given** 已存在多把有效 Key，**When** 各调用方用各自的 Key 请求，**Then** 全部正常通过，互不干扰。
2. **Given** 管理员执行 `oryxos apikey revoke <name>`，**When** 被吊销的 Key 再次用于请求，**Then** 返回 401；其它 Key 不受影响。
3. **Given** 已有若干 Key（含已吊销的），**When** 执行 `oryxos apikey list`，**Then** 输出每把 Key 的名称、可辨识前缀、创建时间、最近使用时间与状态（有效/已吊销），全程无明文。
4. **Given** 请求成功通过某把 Key 认证，**When** 之后查看该 Key，**Then** 其「最近使用时间」已更新（供管理员识别僵尸 Key）。
5. **Given** 尝试用已存在的名称再次 `apikey add`，**When** 命令执行，**Then** 给出清晰报错，不覆盖已有 Key。

---

### User Story 3 - 与既有体系无冲突共存（Priority: P3）

开启 API Key 认证后，其它入口一切照旧：K8s/负载均衡的探活继续无凭据打 `/api/v1/health`；管理台用户照常登录（`/api/v1/auth/*` 走账密），登录后的管理台页面继续正常调 REST 数据接口（浏览器 session 视同有效凭据）；`/admin/**` 的 012 认证体系（Basic Auth + session）完全不受影响。

**Why this priority**: 这是「锁门不能把自己人锁在外面」的回归保障。价值在于不破坏，而非新增能力，故排最后；但不满足它，前两个故事就不能上生产。

**Independent Test**: 开启 API Key 认证（管理台认证也开启）→ 无凭据 curl `/api/v1/health` 得 200；浏览器登录管理台后打开各数据页面，全部正常加载（无 401）；无凭据 POST `/api/v1/auth/login`（正确账密）仍可登录；`/admin/**` 的行为与 012 验收时一致。

**Acceptance Scenarios**:

1. **Given** API Key 认证已开启，**When** 无凭据请求 `/api/v1/health`，**Then** 返回 200（探活豁免）。
2. **Given** API Key 认证已开启，**When** 无 API Key 请求 `/api/v1/auth/*` 子树（登录/登出/me），**Then** 行为与 012 现状一致（这些端点本就靠自身逻辑校验）。
3. **Given** API Key 认证与管理台认证均开启，**When** 已登录的管理台页面（携带有效 session）调用 `/api/v1/**` 数据接口，**Then** 请求正常通过，管理台功能 100% 可用。
4. **Given** API Key 认证已开启但管理台认证关闭，**When** 系统启动，**Then** 输出清晰警告：此组合下管理台数据页面将不可用（浏览器无 session 也无 Key），建议同时开启管理台认证。
5. **Given** API Key 认证已开启，**When** 访问 `/admin/**` 静态资源与登录页，**Then** 行为与 012 现状完全一致（API Key 门禁不覆盖 `/admin/**`）。

---

### Edge Cases

- 明文 Key 在创建后任何时刻（list、日志、审计、报错信息）都不得再出现；日志与错误信息中出现的 Key 相关内容只允许名称与可辨识前缀。
- 认证开启且请求携带的 Key 恰好是「已吊销」的 Key：返回 401，且响应与「从不存在的 Key」不可区分（防探测）。
- Key 校验必须能抵抗计时侧信道：无论 Key 存在与否、格式对错，校验耗时不应有可利用的差异。
- 请求同时携带 API Key 与管理台 session：任一有效即放行；两者皆无效返回 401。
- `oryxos apikey revoke` 一个不存在的名称：清晰报错，退出码非零。
- 浏览器跨域预检（OPTIONS）请求不携带自定义头：预检请求不做 Key 校验，实际请求照常校验（否则合法跨域调用方永远过不了预检）。
- 存量数据库升级：首次以新版本启动时自动补建 Key 存储表，旧库无损（沿用既有 schema 升级模式，不依赖 ORM 自动迁移）。
- 认证关闭但库里已有 Key：Key 静默不生效，不报错（开关是唯一裁决）。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供独立 feature flag（`oryxos.web.apikey.enabled`），默认 `false`。关闭时 `/api/v1/**` 与 CLI 之外的一切行为与现状 100% 一致（回归零破坏）；该开关与 012 的 `oryxos.web.auth.enabled` 相互独立。
- **FR-002**: flag 开启时，系统 MUST 对 `/api/v1/**` 全部端点实施 API Key 认证，豁免清单仅限：`/api/v1/health`（探活）与 `/api/v1/auth/*` 子树（维持 012 现状，由端点自身逻辑校验）。`/admin/**` MUST 完全不受本 feature 影响。
- **FR-003**: 调用方 MUST 能通过请求头携带 Key：`Authorization: Bearer <key>` 或 `X-API-Key: <key>` 二选一，两种写法等效。
- **FR-004**: 认证失败（无 Key / 格式错误 / 不存在 / 已吊销）MUST 统一返回 HTTP 401 与项目统一错误响应体；响应内容 MUST NOT 泄露失败的具体原因类别（防探测），具体原因只进服务端日志（脱敏）。
- **FR-005**: 管理员 MUST 能通过 CLI 管理 Key 全生命周期：`oryxos apikey add <name>`（生成）、`oryxos apikey list`（盘点）、`oryxos apikey revoke <name>`（吊销）。命令风格与既有 `oryxos user` 子命令一致。
- **FR-006**: 生成的 Key MUST 为高熵随机值并带可辨识固定前缀（便于在日志/密钥扫描中识别其为 OryxOS Key）；明文 MUST 仅在 `apikey add` 成功输出中出现一次，此后系统任何界面、日志、存储中 MUST NOT 再出现明文。
- **FR-007**: 持久化 MUST 只存 Key 的不可逆哈希与元数据（名称、可辨识前缀、创建时间、最近使用时间、吊销时间）；名称 MUST 唯一，重名创建 MUST 拒绝。
- **FR-008**: 吊销 MUST 即时生效——吊销完成后的下一次请求即被拒，无缓存窗口；吊销一把 Key MUST NOT 影响其它 Key。
- **FR-009**: Key 校验通过后系统 MUST 更新该 Key 的最近使用时间；更新失败 MUST NOT 阻断业务请求。
- **FR-010**: Key 比对 MUST 使用恒定时间比较，杜绝计时侧信道；对不存在的 Key 与存在但错误的 Key，处理路径耗时 MUST 无可利用差异。
- **FR-011**: flag 开启且管理台认证（012）也开启时，携带有效管理台 session 的请求 MUST 视同通过认证（管理台 SPA 不因锁门而不可用）；API Key 与 session 任一有效即放行。
- **FR-012**: 启动校验：flag 开启但库中无任何有效 Key 时 MUST 启动成功并输出清晰警告（附 CLI 生成提示）；flag 开启但管理台认证关闭时 MUST 输出「管理台数据页面将不可用」的清晰警告。均不静默。
- **FR-013**: Key 存储表 MUST 走既有手工 schema 升级模式（不依赖 ORM 自动迁移），存量数据库首次启动自动补建，旧数据无损。
- **FR-014**: 跨域预检（OPTIONS）请求 MUST 豁免 Key 校验；对应实际请求照常校验。

### Key Entities

- **ApiKey**: 一把机器调用凭证。属性：名称（管理员命名、全局唯一，标识调用方）、可辨识前缀（明文 Key 的头部片段，供盘点与日志对账）、凭证哈希（不可逆，无明文）、创建时间、最近使用时间（可空）、吊销时间（可空；非空即失效）。与 012 的 WebUser（人的账密）相互独立，互不引用。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 默认配置（flag 关闭）下，既有全量测试与手工回归 100% 通过，任何端点行为无差异。
- **SC-002**: flag 开启后，无凭据或无效凭据请求非豁免 `/api/v1/**` 端点，100% 返回 401；有效 Key 请求 100% 正常返回。
- **SC-003**: 携带有效 Key 的请求相对未开启认证时的额外延迟对调用方无感（同环境对比无可感知差异）。
- **SC-004**: 吊销操作完成后，被吊销 Key 的下一次请求即被拒（零缓存窗口）；其余 Key 100% 不受影响。
- **SC-005**: 明文 Key 全生命周期只出现一次（生成命令输出）；存储、日志、list 输出、错误响应中明文出现次数为 0。
- **SC-006**: 双认证（API Key + 管理台认证）全开时，管理台全部页面与操作 100% 可用；`/api/v1/health` 无凭据探活 100% 可用。
- **SC-007**: 管理员从零开始完成「生成 Key → 开启认证 → 调用方接入」全流程不超过 5 分钟（含重启）。

## Assumptions

- **请求头形态**：采用业界通行的 `Authorization: Bearer` 与 `X-API-Key` 双写法等效支持（后者与项目出站 HTTP 工具已有的 `X-API-Key` 约定一致），不引入查询参数携带 Key（易进访问日志，不安全）。
- **管理台兼容取「session 即凭据」**：管理台 SPA 与 REST 同源同端口，锁 `/api/v1/**` 必然波及 SPA 数据请求；选择「有效 session 视同凭据」而非给 SPA 发内置 Key，因为前者不新增密钥暴露面。flag 开启而管理台认证关闭的组合仅告警不阻断——纯机器调用部署（不用管理台）是合法场景。
- **Key 不设过期时间**：本期只做手动吊销，不做自动过期/轮换（留待后续治理版本按需加），避免为低频需求引入定时失效复杂度。
- **不做的**：RBAC、多租户、按 Key 限流、按 Key 细分端点权限（全部留到 v1.0 租户模型定型后）；管理台的 Key 管理页面（本期 CLI-only，管理台页面可后续独立加）；`/api/v1/auth/*` 行为变更（维持 012 现状）。
- **审计口径**：Key 使用情况以「最近使用时间」为最小可用治理信号；不为每次认证单独落审计表（认证不是工具调用/LLM 调用，沿用现有两张审计表口径不变）。
- **依赖**：复用 012-web-auth 落地的配置体系、统一错误响应体、CLI 子命令骨架与 SQLite 手工 schema 升级模式。
