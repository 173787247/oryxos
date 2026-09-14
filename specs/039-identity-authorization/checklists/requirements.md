# Specification Quality Checklist: 企业身份与授权基座

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-09-14
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- FR 中出现的既有契约名（`oryxos.web.rbac.enabled`、`oryxos.web.apikey.enabled`、`/api/v1/auth/**`）、既有类名（`ApiKeyFilterConfig.PROTECTED_URL_PATTERNS`、`SessionApiController`）、表名（`web_users`、`authz_events`）与枚举词表（`VIEWER/EDITOR/ADMIN`、`Action` 11 值）是**对外接口与配置口径**的转述，属契约而非实现细节（沿用 018/020 spec 同一口径）。
- **拍板点（已按合理默认写进 Assumptions，无需 clarify 阻塞）**：① 401 归认证门、403 归授权门；② 拒绝落 `authz_events` 专表、放行不落库、认证事件不做；③ 角色每请求解析不缓存；④ 角色赋值面本刀 CLI-only；⑤ 管理台按钮级隐藏不做；⑥ 未知角色 token 降权忽略而非兜底；⑦ API Key 主体默认不给角色（机器凭证不默认授权），需要时显式配置并始终受 Key 能力上限约束。
- **待 maintainer 裁决（不阻塞本 spec 定稿，但阻断 US1 验收）**：① 边界术语（边界/boundary vs Tenant/Org/Project）；② `/admin/**` 语义是否原样保留（推荐保留）；③ API Key 归属（推荐不做归属建模 + 默认不给权限 + 显式授予 + Key 上限）；④ 路径 → 动作映射边界（推荐按 contracts §4 全表 + 启动期全覆盖校验）——**本项若不裁决，矩阵中 EDITOR/ADMIN 的差异不会在任何请求上生效**；⑤ 角色是否与本刀同批落库（推荐落：V8 加 `web_users.roles` + CLI 赋值 + 默认档收紧为空），否则三档矩阵对管理台账号不生效。
- **关键边界声明（本 spec 最不可退让的一条）**：`oryxos.web.rbac.enabled` 默认 `false` 且关闭时行为逐字节不变（对齐 018 SC-001）；授权必须收口在两扇既有 Filter 内部，**任何新增 URL pattern 的实现都视为违反 FR-004**——`ApiKeyFilterConfig.PROTECTED_URL_PATTERNS` 是单点手维护数组，加一行就多一个漏一行的风险。
- 所有项通过，可进入 `/speckit-clarify` 或直接 `/speckit-plan`。
