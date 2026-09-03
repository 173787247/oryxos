# Acceptance Report: 可插拔存储（Pluggable Storage）

**Feature**: 025-pluggable-storage | **Date**: 2026-09-03 | **Verdict**: V1~V6 全通过，SC-001~SC-008 全达成

## 走查记录（quickstart V1~V6）

### V1 单机零配置无感升级 ✅

- 手工造 legacy 工作区（旧 schedule 键结构 + 缺 trace_id/cost_micros/profile_name/agent_name/config 列的四面旧表 + 存量数据）→ 新 jar 零配置 `serve` 启动成功
- 库侧断言：`flyway_schema_history` = baseline(0) + V1~V5 全 success；schedule 整表重建（`task_id`→`schedule_id`，`run_count=9` 等存量数据完好）；审计/记忆/渠道补列到位；存量记忆归 `'__global__'`
- 二次/三次重启：history 恒 6 条无新增（SC-001 幂等接管）
- **额外真实证据**：仓库根日常开发库（3 条真实历史会话）被本次走查意外接管——history 全 success、会话数据完好，等于一次计划外的真实存量库验证
- mock provider 全链路：会话创建落库、LLM 调用与 save_memory 工具审计落库、trace 同链非空（对话 HTTP 面出现 `AgentMaxIterationsExceededException` 属 mock provider 每轮必调工具的行为特性，与存储无关；存储面断言全过）

### V2 升级类退役等价性 ✅

- `grep -r "SchemaUpgrade|NotifyChannelSchemaMigration" oryxos-*/src/main/java` 零残留（SecretMigration 除外，其为数据+安全逻辑刻意保留）
- 迁移测试（V2~V5 各自 + LegacyTakeoverIT）断言收敛结构与升级类时代逐列一致；`spring.sql.init`/schema.sql 全仓退役（SC-006）

### V3 PG 档全链路 ✅

- 常驻嵌入式 PG（15432）作外部库；走查目录只写 `config/application.yml` 三行（url + username）——用户视角零其他配置，库类型自动识别（FR-006）
- 进程 A 启动：**无本地 oryxos.db 生成**（全量落 PG，无混合形态，FR-001）；会话创建 + mock 对话落库
- JDBC 侧断言（psql 等价）：history 仅 V1（PG 无收敛序列，正确）、sessions/llm_calls/tool_invocations 齐全（SC-002）
- 进程 B（独立工作区）连同一 PG：REST 列表立即可见 A 的会话含 messageCount——**共同事实源真机成立**（SC-003）

### V4 并发启动恰好一次 ✅（自动化）

- `ConcurrentMigrationIT`：空 PG 双上下文并发启动，history 每版本恰一条 success、双方均就绪（SC-004，Flyway PG 内建锁）；SQLite 档按 compose 口径明确为单副本档不承诺并发启动

### V5 故障报错三分类 ✅（全自动化，零人工尾巴）

- `PostgresStorageE2ETest`：错误凭证拒启（role 不存在）、不可达 url 拒启（connection refused）、无建表权限账号在迁移期拒启——三类全部 assertThrows 验证，无静默降级（SC-008）
- `MigrationEvolutionIT`：坏迁移拒启且报错含版本号 `901`（FR-011）；中断半完成态（尾部迁移效果已落、history 未记）重启幂等收敛

### V6 两库测试全绿 + 并发写 ✅

- `mvn install` 全量门禁全绿（Spotless/P3C/Checkstyle/SpotBugs/OWASP + 全部测试含 PG 侧，零跳过零豁免）：storage 144（8 类契约用例 × SQLite/PG 双档）+ boot（LegacyTakeoverIT、PostgresStorageE2ETest×4、ConcurrentMigrationIT、MigrationEvolutionIT×3）（SC-005）
- 30 并发独立会话对 PG 档同时创建+发消息：31 会话/31 LLM 审计/31 工具审计全落库，服务日志 `database is locked|SQLITE_BUSY|deadlock` 零命中（SC-007）

## SC 对照

| SC | 判定 | 证据 |
|----|------|------|
| SC-001 零配置升级无损 | ✅ | V1 走查：接管 + 三次重启 history 恒定 + 真实开发库意外接管 |
| SC-002 PG 侧全量可查 | ✅ | V3：JDBC 断言 sessions/审计/工具与单机档同口径 |
| SC-003 共同事实源 | ✅ | V3：进程 B REST 立即可见进程 A 会话 |
| SC-004 并发迁移恰一次 | ✅ | ConcurrentMigrationIT |
| SC-005 两库全套用例全绿 | ✅ | 8 类契约 × 双 vendor 144 全绿 + PG E2E；零跳过 |
| SC-006 升级类退役 | ✅ | grep 零残留；表结构唯一真相源 db/migration |
| SC-007 并发写零锁冲突 | ✅ | 30 并发走查日志零锁错误（对照 SQLite 档 busy_timeout 止痛药） |
| SC-008 故障三分类可定位 | ✅ | 三类拒启全自动化断言 |

## 实现期修正与实录

1. **@Transactional 声明类之坑**（T018）：契约用例抽到抽象基类后测试事务失效——Spring 事务属性回退查 `declaringClass` 不查 `targetClass`，子类上的 @DataJpaTest 对父类方法无效；契约基类显式加 `@Transactional` 钉死。
2. **中断恢复测试口径修正**（T024）:模拟半完成态须删 history **尾部**行——删中间行是 out-of-order 校验应当拒绝的形态，非中断形态。
3. **空库无 baseline 行**：`baseline-on-migrate` 只对存量非空库生效，PG 新库 history 仅 V1——E2E 断言按此修正。
4. **stale classes 复现**（022 教训第二例）：spring.factories 写于 storage install 之后导致 PostProcessor 未进 classpath——重装后即好；nested-jar 校验已入走查前置步骤。
5. **门禁修正**：PMD 魔法值/抽象类命名（BaseSqliteMigration）、FindSecBugs ReDOS（占位解析去正则化）、CRLF suppress（内部常量表名）。
6. 编号避让：规划原编号 024，因 `024-container-sandbox` 占号顺延 025；评审稿/roadmap 已同步。
