# Data Model: 审计查询接口与报表看板

## 变更总览

两张审计表加 3 列（全部可空，历史行不崩）+ 1 张新表：

| 表 | 变更 | 说明 |
|----|------|------|
| `llm_calls` | +`cost_micros` INTEGER | 成本（微元，1元=100万微元），可空=未计量 |
| `llm_calls` | +`profile_name` VARCHAR(255) | 所属 Agent（profile），冗余，供按 Agent 聚合 |
| `tool_invocations` | +`profile_name` VARCHAR(255) | 所属 Agent（profile），冗余，供按 Agent 聚合 |
| `llm_pricing`（新表） | CREATE TABLE | 模型定价（provider + model 维度） |

## 实体

### LlmCall（`llm_calls`）

既有字段不变：`id, session_id, provider, model, prompt_tokens, completion_tokens, total_tokens, success, error_message, duration_ms, created_at`。

新增：

| 字段 | 类型 | 可空 | 语义 |
|------|------|------|------|
| `cost_micros` | Long | ✅ | 写时定格成本；null = 未计量（失败调用或未定价） |
| `profile_name` | String | ✅ | 所属 Agent；null = 未归属（历史存量行） |

### ToolInvocation（`tool_invocations`）

既有字段不变：`id, session_id, tool_name, input_json, result_json, success, error_message, duration_ms, created_at`。

新增：

| 字段 | 类型 | 可空 | 语义 |
|------|------|------|------|
| `profile_name` | String | ✅ | 所属 Agent；null = 未归属（历史存量行） |

### LlmPricing（`llm_pricing`，新表）

| 字段 | 类型 | 可空 | 语义 |
|------|------|------|------|
| `id` | Long（PK AUTOINCREMENT） | — | 主键 |
| `provider` | VARCHAR(64) | — | 模型所属 provider |
| `model` | VARCHAR(128) | — | 模型名 |
| `prompt_price` | REAL | ✅ | 输入 token 单价（元/百万 token） |
| `completion_price` | REAL | ✅ | 输出 token 单价（元/百万 token） |
| `created_at` | TIMESTAMP | — | 创建时间 |
| `updated_at` | TIMESTAMP | — | 更新时间 |

约束：`UNIQUE (provider, model)`——同一 provider 下同一模型只有一条定价。

## 成本计算规则

- 写时按 `(provider, model)` 查 `llm_pricing`，查到则 `cost_micros = round(prompt_tokens × prompt_price + completion_tokens × completion_price)`。
- 单价「元/百万 token」=「微元/token」，故上式结果单位为微元。
- 查不到定价（该模型未配置）、或 `usage == null`（失败调用）→ `cost_micros = null`（未计量）。
- 任一价格字段缺失 → 缺失侧按 0；两价格都缺失 → `cost_micros = null`。
- 聚合 `SUM(cost_micros)` 天然忽略 null（SQL `SUM` 语义）。

## 迁移

新增 `AuditSchemaUpgrade`（`oryxos-storage`），沿用 `ScheduleSchemaUpgrade` 模式：启动时检查列/表是否存在、缺则补（幂等）。

```sql
ALTER TABLE llm_calls ADD COLUMN cost_micros INTEGER;
ALTER TABLE llm_calls ADD COLUMN profile_name VARCHAR(255);
ALTER TABLE tool_invocations ADD COLUMN profile_name VARCHAR(255);

CREATE TABLE IF NOT EXISTS llm_pricing (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    prompt_price REAL,
    completion_price REAL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE (provider, model)
);

CREATE INDEX IF NOT EXISTS idx_llm_calls_profile ON llm_calls (profile_name);
CREATE INDEX IF NOT EXISTS idx_tool_invocations_profile ON tool_invocations (profile_name);
```

`schema.sql` 同步更新（新库直接建全）；存量库由升级器补列 + 建表。

## 关联关系

- `llm_calls` / `tool_invocations` 与 `sessions` 仍仅靠 `session_id` 关联（不变）。
- 按 Agent 聚合直接 `GROUP BY profile_name`（Java 内存聚合），不 join sessions。
- 无状态调用（`invoke-exec:` 前缀）因冗余 `profile_name` 同样能归属 Agent。
- `llm_pricing` 与 `llm_calls` 通过 `(provider, model)` 逻辑关联（非外键），写时定格成本不依赖定价表后续变更。
