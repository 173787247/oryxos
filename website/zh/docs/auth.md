# 认证

OryxOS 内置**可选**的管理台 HTTP Basic Auth（`/admin/**`）。默认关闭——核心阶段假设受信内网。开启后访问管理台需账密;REST API（`/api/v1/**`）**不受**影响。

> Basic Auth 适合前置 HTTPS 的内网。公网部署须在反向代理终止 TLS 并限制网络暴露——这套最小 auth 是第一道防线,不是边界。

## 工作机制

- **范围**:仅 `/admin/**` 受保护。`/api/v1/**` 的机器调用认证由独立的 [REST API Key](#rest-api-key-认证) 承担,两个开关相互独立。
- **账号**:存 `web_users` SQLite 表。密码 BCrypt 哈希（经 Spring `DelegatingPasswordEncoder` 带 `{bcrypt}` 前缀）——**绝不存明文**,不落配置/日志/git 历史。
- **实时**:账号变更即时生效。每请求重读 DB——无进程内缓存,新账号无需重启即可用。
- **启动校验**:开启 auth 但无 enabled 账号时,启动被阻断,清晰报错指向 `oryxos user add`。

## 配置

auth 由 `application.yml` 的 `oryxos.web.auth` 控制（jar 内默认值,可用 `config/application.yml` 覆盖）:

```yaml
oryxos:
  web:
    auth:
      enabled: false        # 默认关——受信内网
      realm: "OryxOS"        # WWW-Authenticate 头里的 realm 文案
```

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `oryxos.web.auth.enabled` | `false` | 总开关。`false` = 无认证（现状）。`true` = `/admin/**` 启用 Basic Auth。 |
| `oryxos.web.auth.realm` | `OryxOS` | `WWW-Authenticate: Basic realm="..."` 挑战头里的 realm 值。 |

无 `exclude-paths` 配置——filter 只挂在 `/admin/**`,`/api/v1/**`（含 `/api/v1/health`）天然豁免。

## 快速开始

1. **开 auth**（`config/application.yml`）:

   ```yaml
   oryxos:
     web:
       auth:
         enabled: true
   ```

2. **建第一个管理员账号**（无 enabled 账号时启动被阻断）:

   ```bash
   oryxos user add admin
   # Password (>= 8 chars): ********
   # Confirm: ********
   # Created user 'admin'
   ```

3. **启动服务**:

   ```bash
   oryxos serve
   ```

4. **打开管理台** `http://localhost:8080/admin/`——浏览器弹认证。输入刚建的账号。

## 验证

```bash
# 无凭据 → 401 + WWW-Authenticate 挑战
curl -i http://localhost:8080/admin/

# 正确凭据 → 200
curl -u admin:<密码> http://localhost:8080/admin/

# REST API 保持开放（不受 Basic Auth 影响）
curl http://localhost:8080/api/v1/health
```

## 账号管理

见 [`oryxos user` CLI 参考](./cli.md#用户管理):`add`、`list`、`passwd`、`disable`、`delete`。

- `list` **绝不打印密码或哈希**。
- `disable` 保留行但禁止登录（返 401）。`delete` 永久删除。
- 密码须 ≥8 字符;用户名须 ≤64 字符且无空格。

## REST API Key 认证

`/api/v1/**` 的机器调用认证（018-rest-api-key）,与上面的管理台认证独立开关:

```yaml
oryxos:
  web:
    apikey:
      enabled: true   # 默认 false——现状不变
```

- **开启前**先生成 Key:`oryxos apikey add <name>`——明文 `oryx_...` **只显示这一次**,库中仅存 SHA-256 哈希。
- **调用方**任选一种请求头:`Authorization: Bearer <key>` 或 `X-API-Key: <key>`,两者等效。
- **豁免**:`/api/v1/health`（探活）、`/api/v1/auth/*`（管理台登录子树）、OPTIONS 预检;`/admin/**` 完全不受影响。
- **管理台互认**:带有效管理台 session 的请求视同通过认证——双开时管理台数据页照常可用。建议两个开关同时开启,只开 apikey 会在启动日志告警（浏览器既无 session 也无 Key）。
- **生命周期**:`oryxos apikey list` 盘点（无明文）;`oryxos apikey revoke <name>` 吊销即时生效,其它 Key 不受影响。Key 无自动过期,丢失只能吊销重发。

```bash
# 无 Key → 401（统一响应,不泄露失败原因）
curl -i http://localhost:8080/api/v1/profiles
# 带 Key → 200
curl -H "Authorization: Bearer oryx_..." http://localhost:8080/api/v1/profiles
# 探活始终开放
curl http://localhost:8080/api/v1/health
```

## 设计说明

- **不引 Spring Security 全套**:只用 `spring-security-crypto`（密码哈希单 jar）——无 filter chain、无 autoconfig、无 RBAC。`BasicAuthFilter` 是普通 `OncePerRequestFilter`,经 `FilterRegistrationBean` 挂在 `/admin/**`。
- **这不是**:非 SSO、非 RBAC、非多租户、非带登出的 session 登录。这些是扩展阶段能力。密码哈希用 delegating encoder 留了将来升 Argon2 无迁移的路径。
- **HTTP Basic 无登出**——清凭据由浏览器控制。要更丰富的 session 语义,后续 feature 可加登录页,复用同一张 `web_users` 表。
