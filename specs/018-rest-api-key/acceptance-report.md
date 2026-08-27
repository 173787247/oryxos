# 验收报告: REST API Key 认证（018）

**Date**: 2026-08-26（初验）/ 2026-08-27（SC-006 浏览器走查补全） | **验收方式**: 自动化测试（35 用例）+ fat JAR 真机走查（quickstart V1~V6）+ Chromium 无头浏览器双开走查

## 自动化测试

| 套件 | 用例 | 结果 |
|------|------|------|
| `ApiKeyServiceTest`（storage） | 13 | ✅ 全过 |
| `ApiKeyAuthFilterTest`（web） | 12 | ✅ 全过 |
| `ApiKeyStartupCheckTest`（web） | 4 | ✅ 全过 |
| `ApiKeyAuthE2ETest`（boot，真实 HTTP+SQLite） | 5 | ✅ 全过（含双认证共存：真实 HTTP 登录拿 session → 无 Key 调 REST 通过、伪造 session 401） |
| `BasicAuthFilterTest` 新增资源放行用例（web） | 1 | ✅（走查发现的存量缺陷回归钉死） |

## 真机走查（quickstart V1~V6）

环境：WSL2，fat JAR `oryxos-boot-0.1.3-RELEASE.jar`，独立 scratch 工作区。

| 走查 | 步骤 | 观测 | 结论 |
|------|------|------|------|
| V1 回归零破坏 | 默认配置起 serve，无凭据 GET `/api/v1/profiles` | `200` | ✅ |
| V2 生成 Key | `apikey add ci-bot` / 重名再 add / python 查库 | 明文 `oryx_`+42 位仅显示一次并附警告；重名抛 `already exists`；库中 `key_hash` 为 64 位 hex（非明文），`key_prefix`=`oryx_fudnBc3y` | ✅ |
| V3 锁门生效 | `apikey.enabled=true` 起 serve 后四组 curl | 无凭据 `401`+`WWW-Authenticate: Bearer realm="OryxOS"`；错 Key `401`；`Bearer` 与 `X-API-Key` 双写法均 `200`；无 Key/错 Key 401 响应体逐字段一致（仅 timestamp 异） | ✅ |
| V4 生命周期 | 第二把 Key `report`；`revoke ci-bot`；`revoke ghost`；`list` | 吊销后被吊 Key 下一请求即 `401`、`report` 仍 `200`（serve 不重启）；ghost 报 `not found`；list 输出 NAME/PREFIX/STATUS/CREATED_AT/LAST_USED_AT 无明文 | ✅ |
| V5 共存豁免 | health / OPTIONS / 401 防探测 | `/api/v1/health` 无凭据 `200`；`OPTIONS` 预检 `200`；`/api/v1/auth/*` 豁免与 session 互认由 `ApiKeyAuthFilterTest`（authSubtree_exempt / validSessionNoKey_passes）钉死 | ✅ |
| V5+ 浏览器双开走查 | 双 flag 全开，Chromium 无头（playwright-core）完整用户路径 | 未登录访问 `/admin/` 正确跳登录页 → 表单登录 `200` 拿 `oryxos_session` → 进入 SPA 概览页（截图留证：admin 已登录、25 内置 Tool / 2 Provider 等实时数据渲染）→ 带 session 的 `/api/v1/info`/`profiles`/`tools`/`providers`/`sessions/stats` 全 `200`；登录前无 cookie 的同批请求全 `401`（门禁生效的反向证据） | ✅ |
| V6 启动告警 | ① 空库开 apikey；② apikey 开 + auth 关 | ① 启动成功，WARN `no active key found… Run 'oryxos apikey add'`，请求全 `401`；② 启动成功，WARN `Admin console data pages will be unusable…`——均见真实 serve 日志 | ✅ |

## SC 达成情况

| SC | 口径 | 结论 |
|----|------|------|
| SC-001 回归零破坏 | V1 真机 `200` + 全量既有测试随 `mvn verify` 通过 | ✅ |
| SC-002 拒绝与放行 100% | V3 真机四组 + filter 测试 12 用例路径裁决表全覆盖 | ✅ |
| SC-003 认证开销无感 | SHA-256 一次 + UNIQUE 索引查一次（微秒级，弃 BCrypt 的设计裁决见 research R1）；真机 curl 无可感知差异。未做量化压测（analyze G2 裁决：LOW，可接受） | ✅ |
| SC-004 吊销即时 | V4：serve 不重启，吊销后下一请求即 `401`，另一把 Key 不受影响 | ✅ |
| SC-005 明文零泄漏 | 明文仅 `apikey add` stdout 一次；库中 64 位 hex；list/日志（含 revoke INFO 日志）只含前缀 | ✅ |
| SC-006 双开共存 | 三层证据齐备：filter 单测（session 互认/豁免）+ E2E 真实 HTTP（登录→session 调 REST 200、伪造 session 401）+ Chromium 浏览器完整用户路径（登录→SPA 数据页全 200，截图留证） | ✅（全量） |
| SC-007 5 分钟接入 | 真机全流程（add → 开 flag → 重启 → curl 接入）约 3 分钟 | ✅ |

## 实现与设计偏差

- **T013 CLI 独立测试并入**：CLI 子命令自起完整 Spring 上下文（镜像 `UserCommand`，其同样无独立单测），list 无明文/revoke 错误路径断言并入 `ApiKeyServiceTest`，CLI 面行为由真机走查 V2/V4 覆盖。
- **E2E 双认证时序解法**：012 `AuthStartupCheck` 无账号 fail-fast 与测试启动时序冲突，E2E 改为「先建账号、再运行时开启 `WebAuthProperties.enabled`」——filter 每请求读该单例的开关，与生产开启态行为一致，从而在真实 HTTP 链路覆盖双认证共存（初版曾因此仅靠单测覆盖，已补全）。
- **计划中的 `SESSION_COOKIE` 可见性调整无需执行**：`ApiKeyAuthFilter` 与 `BasicAuthFilter` 同包，package-private 常量直接可见。

## 走查发现并修复的存量缺陷（012/013 交互）

浏览器走查发现：`oryxos.web.auth.enabled=true` 时登录页白屏——`BasicAuthFilter` 仅放行 `/admin/login` 前缀，而登录页与 SPA 共用的 JS/CSS bundle 位于 `/admin/assets/`，未登录请求被 401 拦下（curl 可复现），导致**开启管理台认证后任何人都无法登录**。该缺陷在 main 上即存在（012 验收后 013 管理台重建改变了资源路径），与 018 无关，但被本次真实浏览器走查首次暴露。修复：`BasicAuthFilter` 放行 `/admin/assets/**` 静态资源（content-hash 公开前端代码不含数据，数据面由 `/api/v1/**` 认证把守），`BasicAuthFilterTest` 补回归用例。

## 质量门禁

`mvn verify`（Spotless + P3C + Checkstyle + SpotBugs/FindSecBugs + OWASP Dependency-Check）：**BUILD SUCCESS**，全仓 1760 个测试 0 失败 0 错误。过程记录：首轮 Spotless 格式违规经 `spotless:apply` 修复；FindSecBugs 对 `ApiKeyService` 报 3 个 CRLF_INJECTION_LOGS（误报——name 经 validateName 拒绝一切空白字符、prefix 为内部生成 base62）与 2 个 EI_EXPOSE_REP（CreatedKey record 携带实体引用系有意设计），均按项目既有模式以带理由的 `SuppressFBWarnings` 落案。
