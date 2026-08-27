# Quickstart: REST API Key 认证验收

**Feature**: 018-rest-api-key | 契约详见 [contracts/auth-contract.md](contracts/auth-contract.md)

## 前置

```bash
mvn -q -DskipTests package          # 构建 fat JAR
alias oryxos='java -jar oryxos-boot/target/oryxos-boot-*.jar'
```

## V1 — 回归零破坏（US1 场景 1 / SC-001）

```bash
# 默认配置（apikey.enabled 缺省 false）启动 serve
oryxos serve --port 8080 &
curl -s http://localhost:8080/api/v1/profiles     # 期望：200，无凭据可访问，与现状一致
```

## V2 — 生成 Key（US1 场景 2 / SC-005）

```bash
oryxos apikey add ci-bot
# 期望：终端显示一次 oryx_ 开头明文 + 「仅显示这一次」警告
sqlite3 .oryxos/oryxos.db "SELECT name, key_prefix, key_hash FROM api_keys;"
# 期望：只有 64 位 hex 哈希与前缀，无明文
oryxos apikey add ci-bot                          # 期望：重名报错，非零退出码
```

## V3 — 锁门生效（US1 场景 3/4 / SC-002）

```bash
# config/application.yml 置 oryxos.web.apikey.enabled: true 后重启 serve
KEY=<V2 输出的明文>
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/v1/profiles                          # 401
curl -s -o /dev/null -w '%{http_code}\n' -H "X-API-Key: wrong" http://localhost:8080/api/v1/profiles    # 401（响应体与上行一致）
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $KEY" http://localhost:8080/api/v1/profiles  # 200
curl -s -o /dev/null -w '%{http_code}\n' -H "X-API-Key: $KEY" http://localhost:8080/api/v1/profiles     # 200（双写法等效）
```

## V4 — 生命周期（US2 / SC-004）

```bash
oryxos apikey add report                          # 第二把 Key
oryxos apikey list                                # 两行，含 PREFIX/STATUS/LAST_USED_AT，无明文
oryxos apikey revoke ci-bot
curl -s -o /dev/null -w '%{http_code}\n' -H "X-API-Key: $KEY" http://localhost:8080/api/v1/profiles      # 401（即时生效）
curl -s -o /dev/null -w '%{http_code}\n' -H "X-API-Key: $REPORT_KEY" http://localhost:8080/api/v1/profiles  # 200（互不影响）
oryxos apikey revoke ghost                        # 期望：报错，非零退出码
```

## V5 — 共存回归（US3 / SC-006）

```bash
# 双开：oryxos.web.apikey.enabled=true 且 oryxos.web.auth.enabled=true（需先 oryxos user add admin）
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/v1/health   # 200（探活豁免）
curl -s -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<pw>"}'                                  # 200（auth 子树照旧）
# 浏览器：登录 /admin/ 后打开 Agent/审计/渠道各数据页 → 全部正常加载（session 即凭据）
# 预检：curl -s -o /dev/null -w '%{http_code}\n' -X OPTIONS http://localhost:8080/api/v1/profiles → 非 401
```

## V6 — 启动告警（FR-012）

```bash
# ① apikey.enabled=true 但库无有效 Key（全部吊销后）重启 → 日志有 WARN 提示 apikey add，启动成功
# ② apikey.enabled=true 且 auth.enabled=false 重启 → 日志有「管理台数据页面将不可用」WARN，启动成功
```

## 质量门禁

```bash
mvn verify      # Spotless + P3C + Checkstyle + SpotBugs/FindSecBugs + OWASP 全绿
```
