# Contract: 存储可插拔与迁移语义

**Feature**: 025-pluggable-storage | **Date**: 2026-09-03 | 原则 VIII：表结构由 Flyway 管理

## 1. 部署契约

1. **零配置 = 现状**：不提供任何数据库配置时，行为与本特性交付前完全一致（SQLite `oryxos.db` 相对启动目录、WAL、busy_timeout=5000）；无新增必填配置项。
2. **PG 档只需 url + 凭证**：外部配置给 `jdbc:postgresql:` url 与 username/password（password 支持 `${ENV}` 占位）即切换；库类型按 url 自动识别，用户不声明 vendor、不反配 SQLite 专属项。
3. **全量落库**：切 PG 后所有持久化数据（18 张业务表）统一在所配库中，不存在部分数据留在本地 SQLite 的混合形态。
4. **故障可定位**：连接失败 / 凭证错误 / 权限不足 / 迁移失败四类故障各自报错可区分，包含失败迁移版本与库端原因；不静默降级回 SQLite。
5. **支持面**：PostgreSQL 14+；MySQL 等其他库不在本刀承诺内。

## 2. 迁移语义承诺

1. **唯一真相源**：表结构定义只存在于 `db/migration/{vendor}/`；手工升级类全部退役，`spring.sql.init` 关闭。
2. **恰好一次**：迁移应用历史落 `flyway_schema_history`；多副本并发启动时未应用迁移恰好执行一次（PG 内建锁），其余副本等待后继续。
3. **存量无损接管**：任意历史状态的存量 SQLite 库首启动时经幂等序列 V1~V5 收敛到最终结构，数据完好；结构无法识别（非本产品所建）时拒启并报差异。
4. **失败拒启**：迁移失败不带病运行；重启从失败点继续（幂等序列安全重入）。
5. **滚升兼容口径**：V6 起的新迁移在 025~028 期间只做加列/加表/加索引类前向兼容变更；破坏性变更的滚升策略随 028 容器交付定义。

## 3. 数据语义承诺

- 向量载荷（float32 小端序字节）与密文载荷（`enc:v1:` TEXT）两库字节级一致；语义检索与解密行为一致。
- 时间戳、布尔、自增主键的库间表示差异被吸收在 DDL 层，功能面（API 响应、审计查询、管理台）行为一致。
- `oryxos_llm_calls_total` 等指标与审计口径不因库而变。

## 4. 兼容性承诺

- Repository / 实体 / registry / REST / SSE 契约零改动；`ProviderService`、`MemoryService` 等上游无感知。
- SQLite 档启动时间无明显退化；`bin/start.sh` / Docker 入口零改动（配置通道仍是 `config/application.yml` 合并链）。
- CLI 轻命令（session list / knowledge / status）在两种库下同口径工作。
- 宪法 §技术栈持久化行、CLAUDE.md、TechnicalSolution、配置模板随本 PR 同步——文档与行为不脱节。
