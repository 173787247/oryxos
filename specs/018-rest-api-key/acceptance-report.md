# 验收报告: REST API Key 认证（018）

**Date**: 2026-08-26 | **验收方式**: 自动化测试（33 用例）+ fat JAR 真机走查（quickstart V1~V6）

## 自动化测试

| 套件 | 用例 | 结果 |
|------|------|------|
| `ApiKeyServiceTest`（storage） | 13 | ✅ 全过 |
| `ApiKeyAuthFilterTest`（web） | 12 | ✅ 全过 |
| `ApiKeyStartupCheckTest`（web） | 4 | ✅ 全过 |
| `ApiKeyAuthE2ETest`（boot，真实 HTTP+SQLite） | 4 | ✅ 全过 |

## 真机走查（quickstart V1~V6）

环境：WSL2，fat JAR `oryxos-boot-0.1.3-RELEASE.jar`，独立 scratch 工作区。

| 走查 | 步骤 | 观测 | 结论 |
|------|------|------|------|
| V1 回归零破坏 | 默认配置起 serve，无凭据 GET `/api/v1/profiles` | `200` | ✅ |
| V2 生成 Key | `apikey add ci-bot` / 重名再 add / python 查库 | 明文 `oryx_`+42 位仅显示一次并附警告；重名抛 `already exists`；库中 `key_hash` 为 64 位 hex（非明文），`key_prefix`=`oryx_fudnBc3y` | ✅ |
| V3 锁门生效 | `apikey.enabled=true` 起 serve 后四组 curl | 无凭据 `401`+`WWW-Authenticate: Bearer realm="OryxOS"`；错 Key `401`；`Bearer` 与 `X-API-Key` 双写法均 `200`；无 Key/错 Key 401 响应体逐字段一致（仅 timestamp 异） | ✅ |
| V4 生命周期 | 第二把 Key `report`；`revoke ci-bot`；`revoke ghost`；`list` | 吊销后被吊 Key 下一请求即 `401`、`report` 仍 `200`（serve 不重启）；ghost 报 `not found`；list 输出 NAME/PREFIX/STATUS/CREATED_AT/LAST_USED_AT 无明文 | ✅ |
| V5 共存豁免 | health / OPTIONS / 401 防探测 | `/api/v1/health` 无凭据 `200`；`OPTIONS` 预检 `200`；`/api/v1/auth/*` 豁免与 session 互认由 `ApiKeyAuthFilterTest`（authSubtree_exempt / validSessionNoKey_passes）钉死 | ✅ |
| V6 启动告警 | ① 空库开 apikey；② apikey 开 + auth 关 | ① 启动成功，WARN `no active key found… Run 'oryxos apikey add'`，请求全 `401`；② 启动成功，WARN `Admin console data pages will be unusable…`——均见真实 serve 日志 | ✅ |

## SC 达成情况

| SC | 口径 | 结论 |
|----|------|------|
| SC-001 回归零破坏 | V1 真机 `200` + 全量既有测试随 `mvn verify` 通过 | ✅ |
| SC-002 拒绝与放行 100% | V3 真机四组 + filter 测试 12 用例路径裁决表全覆盖 | ✅ |
| SC-003 认证开销无感 | SHA-256 一次 + UNIQUE 索引查一次（微秒级，弃 BCrypt 的设计裁决见 research R1）；真机 curl 无可感知差异。未做量化压测（analyze G2 裁决：LOW，可接受） | ✅ |
| SC-004 吊销即时 | V4：serve 不重启，吊销后下一请求即 `401`，另一把 Key 不受影响 | ✅ |
| SC-005 明文零泄漏 | 明文仅 `apikey add` stdout 一次；库中 64 位 hex；list/日志（含 revoke INFO 日志）只含前缀 | ✅ |
| SC-006 双开共存 | session 互认与 auth 子树豁免由单测钉死；health 真机 `200`。浏览器端双开人工走查未执行（无浏览器环境），部署时按 quickstart V5 复核 | ✅（自动化口径） |
| SC-007 5 分钟接入 | 真机全流程（add → 开 flag → 重启 → curl 接入）约 3 分钟 | ✅ |

## 实现与设计偏差

- **012 零改动比计划更彻底**：`ApiKeyAuthFilter` 与 `BasicAuthFilter` 同包，package-private 的 `SESSION_COOKIE` 直接可见，原计划的常量可见性调整都不需要（contracts §4 已同步更正）。
- **T013 CLI 独立测试并入**：CLI 子命令自起完整 Spring 上下文（镜像 `UserCommand`，其同样无独立单测），list 无明文/revoke 错误路径断言并入 `ApiKeyServiceTest`，CLI 面行为由真机走查 V2/V4 覆盖。
- **E2E 中管理台认证保持关闭**：012 `AuthStartupCheck` 无账号 fail-fast 与测试启动时序冲突，session 互认改由 filter 单测覆盖，语义等价。

## 质量门禁

`mvn verify`（Spotless + P3C + Checkstyle + SpotBugs/FindSecBugs + OWASP Dependency-Check）：**BUILD SUCCESS**，全仓 1760 个测试 0 失败 0 错误。过程记录：首轮 Spotless 格式违规经 `spotless:apply` 修复；FindSecBugs 对 `ApiKeyService` 报 3 个 CRLF_INJECTION_LOGS（误报——name 经 validateName 拒绝一切空白字符、prefix 为内部生成 base62）与 2 个 EI_EXPOSE_REP（CreatedKey record 携带实体引用系有意设计），均按项目既有模式以带理由的 `SuppressFBWarnings` 落案。
