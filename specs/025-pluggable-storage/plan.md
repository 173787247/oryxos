# Implementation Plan: 可插拔存储（Pluggable Storage）

**Branch**: `025-pluggable-storage` | **Date**: 2026-09-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/025-pluggable-storage/spec.md`

## Summary

表结构管理收编进 Flyway：现 schema.sql 平移为 `db/migration/sqlite/V1__baseline.sql`，四个结构升级类（Audit/Memory/Schedule/NotifyChannel）的幂等逻辑平移为 SQLite-only 的 Java migration V2~V5 后退役删除，`baseline-on-migrate=true` + `baseline-version=0` 让存量库与新库走同一条幂等迁移序列（零 baseline 分叉）；`SecretMigration`（数据+安全逻辑）保留为启动 Bean，`@DependsOn` 锚点换成 Flyway。PostgreSQL 成为部署选项：`spring.flyway.locations` 用 `{vendor}` 占位符双轨目录（PG 侧仅 `V1__baseline.sql` 完整结构，14 处 AUTOINCREMENT→IDENTITY、7 处 BOOLEAN 0/1→TRUE/FALSE、BLOB→BYTEA、REAL→DOUBLE PRECISION），`EnvironmentPostProcessor` 按 url 前缀自动清除 SQLite 专属默认（WAL/busy_timeout/方言）——用户配一个 PG url + 凭证即可，SQLite 档零配置零变化。实体/JPQL 层零改动（14 实体全 IDENTITY 两库通用、原生 SQL 0 处）。测试载体已实证定档：zonky embedded-postgres（本机唯一可行，启动 1.7s，两库 Flyway 迁移已端到端跑通），storage 层用例抽共享基类两 vendor 实跑 + PG smoke E2E。

## Technical Context

**Language/Version**: Java 21（Spring Boot 3.5.16，虚拟线程）

**Primary Dependencies**: 新增 `flyway-core` + `flyway-database-postgresql`（Boot BOM 管 11.7.2；SQLite 支持内置于 core——`flyway-database-sqlite` 不存在，已实证）、`org.postgresql:postgresql`（42.7.11，打进 fat jar 即开即用）；test 作用域 `io.zonky.test:embedded-postgres:2.0.7`（linux 二进制已实测可下可跑）

**Storage**: SQLite（默认零配置档，行为与现状一致）/ PostgreSQL 14+（部署选项）；表结构唯一真相源迁移目录 `db/migration/{vendor}/`

**Testing**: JUnit 5——storage 用例共享基类 + sqlite/postgres 双 vendor 子类；迁移测试（存量库 fixture → 启动收敛 → 结构断言）；boot 侧 PG 全链路 smoke E2E；`mvn verify` 全量门禁（OWASP 新增 flyway/postgresql 扫描面）

**Target Platform**: Linux server（单 fat JAR / Docker）

**Project Type**: Maven 多模块单体——storage（迁移目录 + Java migrations + EnvironmentPostProcessor + 升级类退役）、cli（轻命令 url 解析 + OryxOsRuntime 装配锚点）、boot（application.yml + E2E）

**Performance Goals**: SQLite 档启动时间无明显退化（Flyway 校验 history 表毫秒级）；PG 档数十并发会话写入零锁冲突（SC-007）

**Constraints**: SQLite 档零配置零行为变化（FR-002）；存量库无损接管（FR-004）；多副本并发启动迁移恰好一次（FR-005，Flyway PG 内建锁）；凭证走环境变量占位（FR-008）

**Scale/Scope**: 约 7 个新文件（V1 双轨 + 4 个 Java migration + PostProcessor）+ 5 个类删除 + 测试基座改造；18 张表 / 14 处 AUTOINCREMENT / 7 处 BOOLEAN 默认值转写

## Constitution Check

| 原则 | 评估 | 结论 |
|------|------|------|
| I 自实现 ReAct | 存储面特性，循环零接触 | ✅ |
| II Spring AI 边界 | 无涉 | ✅ |
| III Provider 显式映射 | 无涉（providers 表结构不变） | ✅ |
| IV 目录=Agent / Skill | 无涉（文件面工作区不动） | ✅ |
| V 审计 Day One | 强化：审计表结构进 Flyway 版本管理；PG 档审计写入同口径（宪法措辞「写入 SQLite」随 PR 同步为存储中立表述） | ✅ |
| VI 安全是地基 | PG 凭证走 `${ENV}` 占位（既有纪律）；022 密文列 TEXT 两库无缝；无新凭证明文面 | ✅ |
| VII 同步 + 虚拟线程 | Flyway 启动期同步执行；无异步引入 | ✅ |
| VIII 状态外置 / 手工 schema 或 Flyway | **本刀就是该原则的兑现**：原则 VIII 明文「需手工建表脚本或 Flyway」——从前者切到后者；§技术栈「持久化：SQLite」行随 PR 修订为「SQLite（默认）/ PostgreSQL（部署选项）」（PATCH 级） | ✅ |
| 模块约束 | 零新模块；迁移归 storage、配置适配归 storage（EnvironmentPostProcessor 经 spring.factories 注册）、装配改动在 cli；无循环依赖 | ✅ |

**Phase 1 设计后复评**: 设计未引入新违背项，全部通过。无需 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/025-pluggable-storage/
├── plan.md              # 本文件
├── research.md          # Phase 0：9 项裁决（R1~R9，两路实证支撑）
├── data-model.md        # Phase 1：迁移序列 + vendor 转写规则 + 配置面
├── quickstart.md        # Phase 1：V1~V6 验收走查
├── contracts/
│   └── storage-migration.md  # Phase 1：迁移语义承诺 + 兼容性承诺
└── tasks.md             # Phase 2（/speckit-tasks 生成）
```

### Source Code (repository root)

```text
oryxos-storage/
├── pom.xml                                   # 修改：+flyway-core +flyway-database-postgresql +postgresql；test +zonky
├── src/main/resources/
│   ├── db/migration/sqlite/V1__baseline.sql      # 新增：schema.sql 平移（IF NOT EXISTS 保留，llm_pricing 去重）
│   ├── db/migration/postgresql/V1__baseline.sql  # 新增：PG 方言完整最终结构（含 V2~V5 效果 + 全部索引）
│   ├── META-INF/spring.factories                 # 新增：注册 EnvironmentPostProcessor
│   └── schema.sql                                # 删除（内容进 V1）
├── src/main/java/io/oryxos/storage/
│   ├── migration/V2__AuditColumns.java           # 新增：AuditSchemaUpgrade 平移（SQLite-only JavaMigration bean）
│   ├── migration/V3__MemoryAgentColumn.java      # 新增：MemorySchemaUpgrade 平移
│   ├── migration/V4__ScheduleIdentity.java       # 新增：ScheduleSchemaUpgrade 平移（含 legacy 重建 + UUID 键映射）
│   ├── migration/V5__NotifyChannelConfig.java    # 新增：NotifyChannelSchemaMigration 平移
│   ├── config/DataSourceVendorPostProcessor.java # 新增：按 url 前缀清 SQLite 专属默认（R6）
│   ├── AuditSchemaUpgrade.java                   # 删除（退役）
│   ├── MemorySchemaUpgrade.java                  # 删除
│   ├── ScheduleSchemaUpgrade.java                # 删除
│   ├── NotifyChannelSchemaMigration.java         # 删除
│   └── SecretMigration.java                      # 修改：拆除「config 列未就绪」容错（时序泥潭消除）
└── src/test/java/io/oryxos/storage/
    ├── VendorJpaTestBase.java + 组合注解          # 新增：测试基座（8 个 @DataJpaTest 类去重复配置）
    └── migration/*MigrationTest.java              # 改写：升级类单测 → Java migration 测试（fixture 手法保留）

oryxos-cli/
└── src/main/java/io/oryxos/cli/
    ├── OryxOsRuntime.java                    # 修改：@DependsOn 锚点 dataSourceScriptDatabaseInitializer→flyway；
    │                                         #   移除 4 处升级类 Bean 与调用；SecretMigration 依赖改挂 Flyway
    └── command/{Knowledge,SessionList,Status}Command.java  # 修改：url 从配置链解析，Files.exists 仅 sqlite（R7）

oryxos-boot/
├── src/main/resources/application.yml        # 修改：sql.init 关闭；+spring.flyway（locations {vendor}、baseline 0）
└── src/test/java/io/oryxos/boot/PostgresStorageE2ETest.java  # 新增：zonky PG 全链路 smoke（对话→审计→重启恢复）

config/application.yml.example                # 修改：+PG 配置示例段（url/username/${ORYXOS_DB_PASSWORD}）
docker/docker-compose.yml                     # 修改：注释补 PG 选项口径
.specify/memory/constitution.md / CLAUDE.md / docs/TechnicalSolution.md / docs/CliGuide.md  # 同步（R8）
```

**Structure Decision**: 迁移体系整体收编在 oryxos-storage（表结构本就是它的领域）；`OryxOsRuntime` 的改动是拆除——5 处散落的升级副作用（含 refresh 后才跑的 CommandLineRunner 时序泥潭）收敛为 Flyway 单序列，`SecretMigration.java:98-107` 为时序打的容错补丁一并删除。契约面零改动：Repository/实体/registry 接口全部不动（14 实体 IDENTITY 两库通用、原生 SQL 0 处是盘点确认的最大有利条件）。

## 关键设计裁决（详见 research.md）

| # | 裁决 | 要点 |
|---|------|------|
| R1 | 统一幂等迁移序列 | baseline-version=0，存量/新库同走 V1~V5，零分叉；V6 起才写非幂等干净 SQL |
| R2 | 升级类处置 | 四个结构类平移 Java migration 后删除；SecretMigration（数据+安全）保留 Bean 不进 Flyway |
| R3 | vendor 双轨 | `{vendor}` 占位符（已实证）→ sqlite/postgresql 目录；PG 仅 V1 完整结构；转写规则四类 |
| R4 | Flyway 依赖 | SQLite 内置 core（flyway-database-sqlite 不存在，实证）；PG +插件 +驱动，全打 fat jar |
| R5 | PG 测试载体 | zonky embedded-postgres（本机唯一可行，1.7s 启动、两库迁移已实跑全绿） |
| R6 | 配置面自动适配 | EnvironmentPostProcessor 按 url 清 SQLite 专属默认；用户只配 url+凭证 |
| R7 | 硬编码收口 | 3 个 CLI 轻命令 url 走配置链；测试基座去 40 处重复配置 |
| R8 | 宪法/文档同步 | 宪法 §技术栈持久化行 PATCH 修订；CLAUDE.md/TechnicalSolution/模板/CliGuide |
| R9 | 不做的 | MySQL、pgvector、存量搬迁工具、ddl-auto=validate、分布式行为（归 026） |
