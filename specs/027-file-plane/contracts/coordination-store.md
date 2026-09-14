# Contract: CoordinationStore 扩展（027 增量）

**位置**: `oryxos-core/src/main/java/io/oryxos/core/cluster/CoordinationStore.java`（026 既有接口，新增方法；实现 `oryxos-storage/JpaCoordinationStore`）

**总纪律（沿 026）**: 布尔返回 = CAS rowcount 语义；时间基准恒取 DB `CURRENT_TIMESTAMP`；跨库零方言；无 startup 全量清除路径。

## 工作区版本总线

```java
/** 027：递增某域的工作区版本号（DB 原子自增）。必须在文件落盘成功之后调用。 */
void bumpWorkspaceVersion(String domain, String owner);

/** 027：一次读取全部域的当前版本号（表恒 4 行，轮询每 tick 调一次）。 */
Map<String, Long> workspaceVersions();
```

- `domain` ∈ {`agents`, `skills`, `personas`, `knowledge`}；未知域抛 `IllegalArgumentException`。
- 单机档（cluster 关闭）：装配层给管理写路径注入 NOOP 语义（不产生任何写入）；`workspaceVersions()` 无调用方。

## 知识索引构建认领

```java
/** 027：认领某知识库的一次索引构建。首认领或抢过期成功返回 true；他人持有未过期返回 false。 */
boolean tryAcquireIndexBuild(String kbName, long generation, String owner, Duration ttl);

/** 027：构建期间按批续租。false = 认领已失（被接管），调用方必须立即中止并丢弃本代。 */
boolean renewIndexBuild(String kbName, String owner, Duration ttl);

/** 027：释放认领（只删自己的；仅供中止/异常清理路径调用——成功提交由 commitGeneration 在事务内释放）。 */
void releaseIndexBuild(String kbName, String owner);

/** 027：条件提交代次——仍持有认领才生效（校验 claim rowcount + UPSERT committed_generation +
 *  bump knowledge 域 + 释放 claim）。false = 认领已失，未提交、旧代未动。
 *  旧代片段清理（deleteGenerationsBelow）由调用方在提交成功后执行——垃圾回收语义，
 *  中断残留由下次重建清理（实现裁决 2026-09-14：清理归 ChunkStore 所在模块，不跨进协调面事务）。 */
boolean commitGeneration(String kbName, long generation, String owner);

/** 027：读某库已提交代次；空 = 尚无已提交代次（首建前）。 */
OptionalLong committedGeneration(String kbName);
```

## 契约测试义务（CoordinationStoreContractTest 扩展，SQLite/PG 双库各跑）

1. `bumpWorkspaceVersion` 并发递增不丢（两连续 bump 后 version +2）
2. `workspaceVersions()` 返回恒 4 域
3. index claim：互斥（第二认领 false）/ 抢过期（TTL 过后新 owner true）/ fencing（被抢后前任 renew false）
4. `commitGeneration`：持有者提交 true 且代次可读、低代次片段被清；被接管后前任提交 false 且代次未变
5. `releaseIndexBuild` 只删自己的（他人持有时调用无效果）
