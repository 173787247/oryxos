# Data Model: 文件面分布式（027-file-plane）

**Date**: 2026-09-14 | **迁移**: Flyway V8（双 vendor 纯 SQL，`sqlite/V8__file_plane.sql` + `postgresql/V8__file_plane.sql` 同号）

## 1. workspace_versions（变更通知总线）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `domain` | VARCHAR(32) | PK | 固定 4 值：`agents` / `skills` / `personas` / `knowledge` |
| `version` | BIGINT | NOT NULL | 单调递增序号（DB 侧 `version = version + 1` 原子自增），**非**内容版本化 |
| `updated_by` | VARCHAR(128) | NOT NULL | 最后递增者 owner（`instanceId@epoch`，026 同格式） |
| `updated_at` | TIMESTAMP | NOT NULL | DB `CURRENT_TIMESTAMP` |

- 迁移预插 4 行（version=0），运行期只 UPDATE 不 INSERT/DELETE——表恒 4 行，轮询一次全查。
- 写时序约束：管理写路径**文件落盘成功之后**才递增（读到新版本号 ⇒ 必能读到新文件内容，配合 close-to-open）。
- 单机档零写入（bump 为 NOOP）。

**状态转移**: 无状态机，纯单调计数。

## 2. knowledge_build_claims（索引构建认领）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `kb_name` | VARCHAR(128) | PK | 唯一约束即互斥（026 CAS 形态） |
| `owner` | VARCHAR(128) | NOT NULL | `instanceId@epoch` |
| `lease_until` | TIMESTAMP | NOT NULL | DB 时间基准；过期即可被条件抢占（接管重建） |
| `generation` | BIGINT | NOT NULL | 本次构建的目标代次（接管者重新起代，不续用前任的） |

- 认领：INSERT 冲突 → 条件 UPDATE 抢过期（`lease_until < CURRENT_TIMESTAMP`）→ false。
- 续租：`UPDATE ... WHERE kb_name=? AND owner=?`，rowcount=0 即 fencing 失败，本副本立即中止构建。
- 释放：成功提交或失败清理时 `DELETE ... WHERE kb_name=? AND owner=?`（只删自己的）。
- 无 startup 全量清除路径（026 纪律）。

## 3. knowledge_generations（已提交代次）

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `kb_name` | VARCHAR(128) | PK | 每库一行 |
| `committed_generation` | BIGINT | NOT NULL | 检索唯一可见的代次；替换「max(generation) 惰性推断」 |
| `updated_at` | TIMESTAMP | NOT NULL | 提交时间 |

- **条件提交**（同事务）：① `UPDATE knowledge_build_claims SET ... WHERE kb_name=? AND owner=?` 校验仍持有（rowcount=1）；② UPSERT `committed_generation`；③ `deleteGenerationsBelow(kb, newGen)`；④ bump `knowledge` 域版本号；⑤ 释放认领。任一步失败整体回滚，旧代不受影响。
- 空态兼容：无行 = 库尚无已提交代次（首建前检索返回空，与现状口径一致）。
- 各副本对该表的读走本地缓存，总线 `knowledge` 域版本变化即失效重读。

## 4. 既有表/结构不变项（明确声明）

- `knowledge_documents` / `knowledge_chunks`：结构不动；`generation` 列语义不变，「活跃代次」的**判定来源**由推断改为表 3。
- `session_turn_leases` 等 026 四表：不动。
- `.oryxos/` 目录结构：不动（文件本体仍是内容载体；本刀不做文件入库）。
- `ProfileRegistry` / `SkillRegistry`：内存结构不动，新增重载入口（`reconcileAll` / `replaceAll`）。

## 5. 配置项（ClusterProperties 增量）

| 键 | 默认 | 说明 |
|----|------|------|
| `oryxos.cluster.workspace-poll-interval` | `1s` | 版本号轮询周期（SC-001 的 3s 含 1 轮询 + 重载余量） |
| （复用）`oryxos.cluster.lease-ttl` | `30s` | 索引认领 TTL 与续租节奏复用既有键，不另立参数 |

## 6. 实体/Repository 增量（oryxos-storage）

- `WorkspaceVersionEntity` + `WorkspaceVersionRepository`（含 `@Modifying` 自增 UPDATE 与全量 SELECT）
- `KnowledgeBuildClaimEntity` + `KnowledgeBuildClaimRepository`（CAS 三式，仿 `TurnLeaseRepository`）
- `KnowledgeGenerationEntity` + `KnowledgeGenerationRepository`（条件提交 UPSERT）
- `JpaCoordinationStore` 注入以上三个 Repository 实现 `CoordinationStore` 新方法（时间基准恒 DB `CURRENT_TIMESTAMP`，跨库零方言沿既有 `rethrowUnlessConstraintViolation` 纪律）
