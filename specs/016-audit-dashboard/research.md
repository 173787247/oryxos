# Research: 审计查询接口与报表看板

技术决策记录。每个决策给出「选择 / 理由 / 备选」。

## D1. 成本计算时机：写时定格

- **Decision**: LLM 调用落库时按「当时单价 × 用量」算出成本并写入 `llm_calls.cost_micros`。
- **Rationale**: 成本是历史事实——上个月看报表是 100 元，这个月模型降价后不能变成 80 元。写时定格保证历史成本稳定、聚合查询无需 join 价格、可直接 SUM。
- **Alternatives**: 查询时实时算（不改审计表、无迁移，但历史成本随价格漂移、每次查询要 join 价格）。

## D2. 成本存储单位：微元整数

- **Decision**: `cost_micros INTEGER`，1 元 = 1,000,000 微元。
- **Rationale**: 单价「元/百万 token」恰好等于「微元/token」，故 `cost_micros = round(promptTokens × promptPrice + completionTokens × completionPrice)` 全程整数，无浮点累加误差。SQLite `INTEGER` 原生 64 位，容量远超企业场景。
- **Alternatives**: `REAL` 存元（浮点累加误差在按 Agent 聚合上万次调用时被放大）；`DECIMAL`（SQLite 无此类型）。

## D3. Agent 归属：冗余 profile_name（而非 join sessions）

- **Decision**: `llm_calls` / `tool_invocations` 各加 `profile_name` 列，写入时从 `profile.name()` 带上。
- **Rationale**: 无状态调用 `/agents/{name}/invoke` 的 session 是 `invoke-exec:UUID`，不落 `sessions` 表——若走 join sessions，这些审计行成孤儿、无法归属 Agent。冗余后聚合单表完成、自包含、无孤儿。且成本迁移本就要给 `llm_calls` 加列，顺带加 `profile_name` 零额外迁移成本。
- **Alternatives**: join `sessions`（`session_id → profile_name`，无状态调用丢行）；字符串反解 `session_id`（`channel:user:profile` 的 `:` 不可靠，channel/user 本身可能含 `:`）。

## D4. 聚合方式：Java 内存聚合（不引入 SQL GROUP BY）

- **Decision**: Repository 只补派生查询方法（`findByCreatedAtBetween` 等），聚合在 Service 里 Java for 循环做。
- **Rationale**: 全项目已有范式（`KnowledgeMetricsService` 就是这么做的），无任何 SQL GROUP BY；SQLite 方言下日期/聚合函数支持有限。万级记录内存聚合秒级，性能足够。
- **Alternatives**: JPQL `@Query` GROUP BY（SQLite 方言函数支持有限，且偏离现有范式）；原生 SQL（项目无 nativeQuery 先例）。

## D5. 迁移方式：schema.sql + 专门升级器

- **Decision**: 5 列加在 `schema.sql`（`CREATE TABLE IF NOT EXISTS` 权威），存量库由新增 `AuditSchemaUpgrade` 升级器执行 `ALTER TABLE ADD COLUMN`（新列可空）。
- **Rationale**: 宪法 VIII——SQLite `ALTER TABLE` 弱，`ddl-auto: none`，加列走手工 DDL + 升级器。已有 `ScheduleSchemaUpgrade`（385 行）、`MemorySchemaUpgrade` 先例可循。
- **Alternatives**: Flyway（项目尚未引入，且现有升级器模式已成熟）；Hibernate ddl-auto（宪法禁止）。

## D6. 定价维度：模型级（provider + model）

- **Decision**: 单价针对具体模型设置，键为 `(provider, model)` 组合，存独立 `llm_pricing` 表；不再挂 provider 级单价。
- **Rationale**: 同一 provider 下不同模型价格差异巨大（deepseek-chat vs deepseek-reasoner；gpt-4o vs gpt-5.5），provider 级单价无法真实反映成本。`llm_calls` 表既有 `provider` 与 `model` 两列，写时按二者精确查价。用组合键而非单一 model 名，避免不同 provider 用同名 model 时歧义。
- **Alternatives**: 挂 providers 表 provider 级单价（无法区分同 provider 不同模型）；单一 model 名主键（跨 provider 同名冲突）。

## D7. 价格配置入口：模型定价管理台 CRUD

- **Decision**: 新增独立模型定价 CRUD（`PricingApiController` + `LlmPricing` 实体），管理台按 provider 分组配置各模型单价。
- **Rationale**: 价格是运营数据、随模型降价频繁变动，业务人员需自助调价、无需重启；模型定价是独立实体，不塞进 provider 表单。
- **Alternatives**: 仅 application.yml（重启生效，不灵活）；挂 Provider 表单（模型是 provider 的下级，独立管理更清晰）。

## D8. 前端图表：手写 SVG/CSS

- **Decision**: 分布图表手写 SVG/CSS 实现（横向条形图展示按模型/工具/Agent 分布），不引入 ECharts 等图表库。
- **Rationale**: 项目约束「不为漂亮引大型 UI 库盖过 token」（oryxos-admin-ui skill）；前端是单文件 App.vue，复用现有 KPI 卡片、时间窗切换、表格即可。
- **Alternatives**: ECharts/Chart.js（引入体积与主题不匹配）；纯 CSS 条形（比 SVG 更受限，不便于下钻标注）。

## D9. 工具调用成本：不折算

- **Decision**: `tool_invocations` 只冗余 `profile_name`（供按 Agent 聚合），不折算金额成本。
- **Rationale**: 工具无统一计价模型（本地 IO、HTTP、Shell 成本各异）；工具报表只看次数/成功率/耗时。
- **Alternatives**: 给工具加价格（无合理定价依据，过度设计）。
