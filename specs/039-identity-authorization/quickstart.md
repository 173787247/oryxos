# Quickstart: 企业身份与授权基座验收走查

**Feature**: 039-identity-authorization | 契约见 [contracts/authorization-contract.md](contracts/authorization-contract.md)

前置：`mvn -q spotless:apply && mvn install`（确认新 fat jar——nested jar 校验含 `WebUserRolesMigration` 与 `V8__web_user_roles.sql`；026 的 stale-jar 教训：boot E2E 前恒重装上游模块）。

```bash
alias oryxos='java -jar oryxos-boot/target/oryxos-boot-*.jar'
# 独立 scratch 工作区，避免污染既有 .oryxos/
```

## V1 默认档零回归（US3 场景 3 / SC-001）

```bash
oryxos user add admin      # 建账号（沿用 012 CLI）
oryxos serve --port 8080 & # 不给任何 RBAC 配置（rbac.enabled 缺省 false）
curl -s -o /dev/null -w '%{http_code}\n' -H "X-API-Key: $KEY" http://localhost:8080/api/v1/agents   # 200
# 库侧：web_users.roles 全为 VIEWER（V8 默认值），但没有任何请求因此被拒
sqlite3 .oryxos/oryxos.db "SELECT username, roles FROM web_users;"
sqlite3 .oryxos/oryxos.db "SELECT COUNT(*) FROM authz_events;"      # 期望 0（授权层未启用，零写入）
mvn -q verify                                                      # 全量既有测试零改动全绿
```

> 阶段一提示（角色/审计表未落库时）：`roles` 列与 `authz_events` 表尚不存在，上面两条库查询会报 `no such column/table`——这是预期现象，按 [acceptance-report.md](acceptance-report.md) §三 的差距清单判定；把 `SELECT` 换成「`web_users` 列清单」查询同样可确认零结构变更。

## V2 角色生效（US1 / SC-002）

```bash
oryxos user add viewer && oryxos user add editor && oryxos user add ops
oryxos user role viewer VIEWER
oryxos user role editor EDITOR
oryxos user role ops ADMIN
oryxos user list                                            # 期望：多出 ROLE 列且显示三档
# 配置：oryxos.web.apikey.enabled=true 且 oryxos.web.rbac.enabled=true，重启 serve
# 角色落库尚未落地时（阶段一）改为起三次进程，分别把
# oryxos.web.rbac.roles.default-user-roles 设为 [VIEWER] / [EDITOR] / [ADMIN]，重放下面同一批断言
LOGIN() { curl -s -c /tmp/$1.jar -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' -d "{\"username\":\"$1\",\"password\":\"$2\"}"; }
LOGIN viewer <pw>; LOGIN editor <pw>; LOGIN ops <pw>

# 只读档：读通、写拒
curl -s -o /dev/null -w '%{http_code}\n' -b /tmp/viewer.jar http://localhost:8080/api/v1/agents    # 200
curl -s -o /dev/null -w '%{http_code}\n' -b /tmp/viewer.jar -X POST http://localhost:8080/api/v1/agents \
  -H 'Content-Type: application/json' -d '{"name":"t","description":"d"}'                          # 403
# 编辑档：建 Agent 通、治理面拒
curl -s -o /dev/null -w '%{http_code}\n' -b /tmp/editor.jar -X POST http://localhost:8080/api/v1/agents \
  -H 'Content-Type: application/json' -d '{"name":"t","description":"d"}'                          # 200（或 400 校验类，非 403）
curl -s -o /dev/null -w '%{http_code}\n' -b /tmp/editor.jar -X POST http://localhost:8080/api/v1/tool-policy/rules \
  -H 'Content-Type: application/json' -d '{"ruleType":"GLOBAL_DENY","pattern":"shell"}'             # 403
# 管理档：治理面通
curl -s -o /dev/null -w '%{http_code}\n' -b /tmp/ops.jar -X POST http://localhost:8080/api/v1/tool-policy/rules \
  -H 'Content-Type: application/json' -d '{"ruleType":"GLOBAL_DENY","pattern":"shell"}'             # 200
```

## V3 拒绝留痕与可解释（US2 / SC-003、SC-004）

```bash
# 前置：阶段二（authz_events 落库）完成后本节的库查询才成立；未落库时改为在日志中核对同名字段
# 承接 V2：至少已有 2 次拒绝（viewer 建 Agent、editor 改策略）
sqlite3 .oryxos/oryxos.db \
  "SELECT principal_kind, principal_id, action, resource_type, reason, request_method, request_path, created_at FROM authz_events ORDER BY id;"
# 期望：每行主体类别/标识齐全（USER:viewer、USER:editor），无需凭证明文，理由是完整中文句子
sqlite3 .oryxos/oryxos.db "SELECT COUNT(*) FROM authz_events;"           # 期望 = 拒绝次数（放行不落库）
curl -s -b /tmp/viewer.jar http://localhost:8080/api/v1/auth/me          # 期望含 roles 与 rbacEnabled
curl -s -o /dev/null -w '%{http_code}\n' -b /tmp/viewer.jar http://localhost:8080/api/v1/agents/nonexistent  # 403（无权与不存在不可区分）
# 审计失败不改裁决：把库目录临时置为只读后重放一次越权请求 → 仍 403，日志出现 ERROR（恢复后继续）
```

## V4 API Key 主体与能力上限（US1 场景 5 / SC-002）

```bash
oryxos apikey add ci-bot                                  # 明文仅显示一次
KEY=<V4 输出的明文>
curl -s -o /dev/null -w '%{http_code}\n' -H "X-API-Key: $KEY" http://localhost:8080/api/v1/agents   # 403（默认空角色 = 机器凭证不默认授权）
# 配置 oryxos.web.rbac.roles.default-api-key-roles: [EDITOR] 重启后重放：
curl -s -o /dev/null -w '%{http_code}\n' -H "X-API-Key: $KEY" http://localhost:8080/api/v1/agents             # 200（运行/资产面放行）
curl -s -o /dev/null -w '%{http_code}\n' -H "X-API-Key: $KEY" -X POST http://localhost:8080/api/v1/tool-policy/rules \
  -H 'Content-Type: application/json' -d '{"ruleType":"GLOBAL_DENY","pattern":"shell"}'                       # 403（Key 上限：不改治理）
# 把 default-api-key-roles 改成 [ADMIN] 重启后重放上面两条：仍是 403 + 403（上限不可绕过）
# 库中审计行的 principal_id 期望为 ci-bot（名称），全文检索不应出现明文 Key：
sqlite3 .oryxos/oryxos.db "SELECT principal_id, action, reason FROM authz_events ORDER BY id DESC LIMIT 3;"
```

## V5 启动校验与端点全覆盖（FR-010、FR-012 / SC-005）

```bash
# ① rbac.enabled=true 且 apikey.enabled=false 启动 → 拒绝启动，报错指路开 apikey
# ② rbac.enabled=true 且库中无 ADMIN（把 ops 降为 EDITOR 后）启动 → 拒绝启动，报错指路 user role
# ③ 手改一行角色为 ADMINX 后启动 → 启动成功 + WARN，该账号按剩余合法角色裁决（甚至降为拒绝）
sqlite3 .oryxos/oryxos.db "UPDATE web_users SET roles='ADMINX' WHERE username='viewer';"
# ④ 全覆盖校验：临时在一个 Controller 上新加 /api/v1/xxx 端点（不改映射表）→ 启动即拒并打印路径；
#    撤销该临时改动后恢复正常启动（此步在实现期以单测形式钉死，真机抽查一次即可）
```

## V6 管理台共存走查（US3 / SC-002、SC-006）

1. 三开关全开（`web.auth.enabled` + `web.apikey.enabled` + `web.rbac.enabled`），Chromium 无头/真浏览器登录 `/admin/`
2. 以 ADMIN 账号走查：概览、Agent、会话、渠道、审计各数据页正常渲染（无 401/403 回归；截图留证）
3. 以 VIEWER 账号走查：只读页正常；写操作被拒时页面提示来自统一信封的人话理由（不是白屏）
4. `curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/v1/health` → 200（无凭据探活豁免）
5. `curl -s -X POST http://localhost:8080/api/v1/auth/login ...` → 200（登录子树的豁免在 RBAC 开启后仍成立）

## 收尾

acceptance-report.md 落卷（V1~V6 + SC-001~SC-007 对照表；未走通项如实标注原因）；文档同步核对：`CLAUDE.md`（`oryxos.web.rbac.*` 配置段与授权口径）、`config/application.yml.example`、`docs/CliGuide.md`（`oryxos user role`）、`website/docs/` 与 `website/zh/docs/` 成对更新。
