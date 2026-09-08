# Quickstart: 可插拔存储验收走查

**Feature**: 025-pluggable-storage | 契约见 [contracts/storage-migration.md](contracts/storage-migration.md)

前置：`mvn -q spotless:apply && mvn install`（产出 0.1.4-RELEASE fat jar，**务必确认跑的是新 jar**——022 有过旧 jar 假象教训）。

## V1 单机零配置无感升级（US2 / SC-001）

1. 用旧版本 jar 在干净目录产生带数据工作区：一轮对话 + save_memory + 注册一个 provider
2. 换新 jar 零配置启动：`java -jar oryxos-boot/target/oryxos-boot-*.jar serve`
3. 断言：启动成功；`sqlite3 oryxos.db "select version, success from flyway_schema_history"` 可见 baseline(0) + V1~V5 全成功；旧会话/记忆/provider 数据完好；新对话读写正常
4. 再次重启：history 无新增行，启动时间无明显变化（SC-001）

## V2 存量升级类退役等价性（US3 / SC-006）

1. `grep -r "SchemaUpgrade\|NotifyChannelSchemaMigration" oryxos-*/src/main/java` → 零命中（SecretMigration 除外）
2. 用测试 fixture 造「缺 trace_id/agent_name/config 列 + legacy schedule 键」的旧库，新 jar 启动 → 结构收敛与升级类时代逐列一致（迁移测试断言）
3. 新装空库最终结构（表+列+索引集合）与存量库收敛后一致

## V3 PG 档全链路（US1 / SC-002、SC-003）

1. 起 PG（zonky smoke 已验证机制；真机走查可用任一 PG 14+ 实例）；`config/application.yml` 按模板配 url/username，`export ORYXOS_DB_PASSWORD=...`
2. 启动 → psql 侧断言：`\dt` 18 张业务表 + flyway_schema_history（仅 baseline+V1）
3. 一轮含工具调用对话 → psql 查 sessions/llm_calls/tool_invocations/memory_entries 数据齐全，trace_id 同链（SC-002）
4. 起第二个进程连同一 PG（换端口）→ 能查到第一个进程的会话与审计（SC-003）；两进程同时保持运行各自对话互不干扰
5. 语义回归：记忆 recall（向量 BYTEA 路径）、provider 注册（api_key 密文 `enc:v1:` 解密正常）、管理台审计页

## V4 并发启动迁移恰好一次（SC-004）

空 PG 库，同时拉起两个进程 → flyway_schema_history 每版本恰一条成功记录；两进程均正常就绪（后者等锁）

## V5 故障报错三分类（SC-008）

1. url 指向不可达主机 → 报错含连接失败与 url 指向
2. 错误密码 → 报错指明认证失败
3. 只读账号连空库 → 报错指明权限/建表失败与迁移版本
均拒启，无静默降级

## V6 两库测试全绿 + 并发写（SC-005、SC-007）

1. `mvn verify` 全量门禁（Spotless/P3C/Checkstyle/SpotBugs/OWASP + 全部测试含 PG vendor 子类与 PG smoke E2E）零跳过
2. 并发写：PG 档 30 并发独立会话同时发消息 → 零 `database is locked` 类锁冲突错误（对照现状 SQLite 档同压力的锁等待）

## 收尾

acceptance-report.md 落卷（V1~V6 + SC 对照）；文档同步核对：宪法/CLAUDE.md/TechnicalSolution/CliGuide/application.yml.example/docker-compose 注释。
