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

见 [`oryxos user` CLI 参考](./cli.md#用户管理):`add`、`list`、`role`、`passwd`、`disable`、`delete`。

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

## 授权（RBAC）

认证回答"你是谁",授权回答"你能做什么"。两者独立开关——**认证开,授权才有对象**:`web.auth` 与 `web.apikey` 都关时没有任何门产出主体,请求恒为匿名,此时授权不会把单机零配置部署锁死（刻意设计）。

```yaml
oryxos:
  web:
    rbac:
      enabled: true          # 默认 false——不装授权层,行为与引入前一致
      deny-anonymous: true   # 默认 true;授权开启后未认证主体直接拒绝
      roles:
        default-user-roles: []      # 默认空——无角色即拒绝
        default-api-key-roles: []   # 默认空——机器凭证不给默认权限
```

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `oryxos.web.rbac.enabled` | `false` | 总开关。`true` = `/api/v1/**` 与 `/admin/**` 的请求主体按角色裁决。 |
| `oryxos.web.rbac.deny-anonymous` | `true` | 未认证主体是否直接拒绝。 |
| `oryxos.web.rbac.roles.default-user-roles` | 空 | 账号未自带角色时的兜底档;**空 = 无角色即拒绝**。 |
| `oryxos.web.rbac.roles.default-api-key-roles` | 空 | API Key 未自带角色时的兜底档;默认不给机器凭证任何权限。 |

开启后,每个请求的主体交给**唯一决策点** `AuthorizationService`:按路径映射到动作裁决;**未登记的受保护路径 fail-closed 拒绝**。拒绝返回 **403**（与认证失败的 401 区分）,结构化日志留痕并落 `authz_events` 表可筛。渠道入站、审批回调、健康检查与 `/api/v1/auth/**` 只认证不裁决。

### 三档能做什么

| 角色 | 能做什么 |
| --- | --- |
| `VIEWER` | 只读:看工作区与审计。 |
| `EDITOR` | 在 VIEWER 之上可干活:跑 Agent,管 Agent / 知识库 / Skill / 自己的会话。 |
| `ADMIN` | 在 EDITOR 之上管边界:成员、渠道、策略、工作区设置。 |

逐级包含（VIEWER ⊆ EDITOR ⊆ ADMIN）。**API Key 主体另有硬上限**:即使授予 `ADMIN`,也不得改成员与策略——Key 是长期有效、可复制到任意环境的机器凭证,不与人账号放在同一权限上限。主体自带角色优先,**不取并集**（否则 VIEWER 账号可凭同一请求里的 Key 角色提权）。

```bash
oryxos user role admin ADMIN   # 设角色（覆盖式）
oryxos user list               # ROLE 列看当前档位
```

新建账号默认 `VIEWER`,要干活须显式提档;角色每请求从库重解析、不缓存,**改档或撤权下一次请求即生效**。

### 启动校验（误配拒启）

`rbac.enabled=true` 时启动做两项校验,不满足**直接拒绝启动**并指出改法:

- **apikey 认证未开** —— 没有门产出主体,授权会静默失效;
- **库中没有任何 ADMIN 账号** —— 治理面会锁死,报错提示 `oryxos user role <name> ADMIN`。

`web.auth.enabled=false` 只告警（管理台数据页将无 session 凭据可用）。

## 设计说明

- **不引 Spring Security 全套**:只用 `spring-security-crypto`（密码哈希单 jar）——无 filter chain、无 autoconfig。`BasicAuthFilter` 是普通 `OncePerRequestFilter`,经 `FilterRegistrationBean` 挂在 `/admin/**`;授权（RBAC）同样不经 Spring Security,而是 039 自己的 `RbacEnforcer` + `AuthorizationService`。
- **边界**:本文只讲认证。SSO/OIDC（040）与授权 RBAC（039,见上节）均已内置;管理台另有带登出的 session 登录（012,`POST /api/v1/auth/logout`）。密码哈希用 delegating encoder 留了将来升 Argon2 无迁移的路径。
- **HTTP Basic 本身无登出**——清凭据由浏览器控制。管理台另有 session 登录页（`/admin/login`）,复用同一张 `web_users` 表,`POST /api/v1/auth/logout` 清 session 与 cookie（幂等）。
