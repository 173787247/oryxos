# Data Model: REST API Key 认证

**Feature**: 018-rest-api-key | **Date**: 2026-08-26

## api_keys（新表）

机器调用凭证。与 `web_users`（人的账密）相互独立，互不引用。

```sql
-- api_keys：REST API 机器调用凭证（018-rest-api-key）
-- 新表，CREATE TABLE IF NOT EXISTS，非 ALTER，存量库无迁移风险（FR-013）。
CREATE TABLE IF NOT EXISTS api_keys (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name         VARCHAR(64)  NOT NULL UNIQUE,   -- 管理员命名，标识调用方（FR-007）
    key_prefix   VARCHAR(16)  NOT NULL,          -- 明文头部片段 oryx_XXXXXXXX，供盘点/日志对账
    key_hash     VARCHAR(64)  NOT NULL UNIQUE,   -- SHA-256 hex，无明文（FR-007）
    created_at   TIMESTAMP    NOT NULL,
    last_used_at TIMESTAMP,                      -- 可空；校验通过后节流更新（FR-009）
    revoked_at   TIMESTAMP                       -- 可空；非空即失效（FR-008）
);
-- 无需显式索引：key_hash 的 UNIQUE 约束在 SQLite 下自带隐式索引，校验查找即走该索引
```

### 字段约束

| 字段 | 约束 | 来源 |
|------|------|------|
| `name` | 非空、全局唯一、≤64 字符；重名创建拒绝 | FR-007、US2 场景 5 |
| `key_prefix` | `oryx_` + 明文随机部分前 8 位；list/日志中唯一允许出现的 Key 片段 | FR-006、R1 |
| `key_hash` | SHA-256(完整明文) 的 hex；唯一索引，校验按此列查找 | R1、R2 |
| `last_used_at` | 分钟级精度即可（60s 内存节流）；更新失败不阻断请求 | FR-009、R6 |
| `revoked_at` | 吊销即写入；校验时非空一律拒绝，无缓存窗口 | FR-008 |

### 生命周期（状态转移）

```
（不存在） --apikey add--> 有效（revoked_at IS NULL）
有效 --请求校验通过--> 有效（last_used_at 刷新，节流）
有效 --apikey revoke--> 已吊销（revoked_at = now，终态，不可恢复）
```

- 无自动过期（Clarifications Q3）；无删除操作（保留吊销记录供盘点，list 显示状态）。
- 认证开关关闭时，表中数据静默不生效（Edge Case：开关是唯一裁决）。

## Java 侧映射（oryxos-storage，镜像 WebUser 三件套）

| 类 | 职责 |
|----|------|
| `ApiKey`（`@Entity`） | 表映射，字段同上 |
| `ApiKeyRepository`（Spring Data JPA） | `findByKeyHash`、`findByName`、`findAll`、`existsByName` |
| `ApiKeyService` | `create(name)` → 生成明文+落哈希、返明文（仅此一次）；`verify(plaintext)` → 哈希查找 + 恒时复核 + 吊销判定 + last_used 节流更新；`revoke(name)`；`list()`；`hasActiveKey()`（启动校验用） |

## 不新增的

- 不加 `expires_at`（Clarifications Q3：不做过期，需求出现再加列——新列可走既有 SchemaUpgrade 模式）。
- 不加认证事件审计表（spec Assumptions：沿用两张审计表口径，Key 治理信号 = `last_used_at`）。
- `web_users` / `web_sessions` 零改动。
