# Quickstart: 文件面分布式（027-file-plane）验证指南

**前提**: 027 实现完成；本地 PG（沿 026 走查的常驻 15432 实例）；`mvn clean install` 全绿。

## V1 自动化验收（全部门禁内）

```bash
mvn -f pom.xml clean install -DskipITs=false
```

| 面 | 载体 | 断言要点 |
|----|------|---------|
| CAS/版本号契约（双库） | CoordinationStoreContractTest 扩展 | 版本自增不丢、claim 互斥/抢过期/fencing、条件提交 rowcount |
| US1 秒级可见 | FilePlaneVisibilityIT（双上下文 + 共享 PG + 共享临时工作区） | A 建/改/删 Agent 与 Skill，B 3s 内列表与调用一致；重启副本对账一致 |
| US2 恰好一次 | KnowledgeExactlyOnceIT | 双副本同触发 rebuild 恰一执行；kill 持有者超时接管；检索恒读已提交代次 |
| US3 原子写 | AtomicFiles 单测 + 写路径单测 | 中途失败无半写文件（临时文件残留可清理、目标文件完整） |
| 单机档零回归 | 既有全量测试 + 零协调写断言 | watcher 行为不变；无版本号写入、无认领写入 |
| 误配回归 | ClusterStartupCheckTest | 026 既有 5 用例不变绿 |

## V2 真机走查（双真进程 jar + 共享 PG + 共享工作区目录）

```bash
# 同机双进程用同一目录模拟共享卷（NFS 语义走查见 V4）
ORYXOS_ROOT=/srv/oryxos-shared/.oryxos  # 两副本同值
# 副本 A（8080）与 B（8081）分别以 cluster.enabled=true、不同 instance-id 启动
```

1. **US1**: 经 A 的 `POST /api/v1/agents` 建 Agent → 3s 内 `GET :8081/api/v1/agents` 可见 → 经 B 调用该 Agent 正常应答；经 A 改配置 → B 下一轮对话用新配置；经 A 删除 → B 列表消失。
2. **US2**: 双副本几乎同时 `POST /api/v1/knowledge/{name}/reindex` → 日志恰一副本执行、另一副本返回「构建进行中」；构建中 kill 执行副本 → lease-ttl 过后另一副本重新 reindex 成功 → 两副本 `GET /api/v1/knowledge/{name}` 状态一致、检索结果一致。
3. **FR-011**: SSH 直接改共享卷上某 AGENT.md → `POST /api/v1/workspace/refresh` → 两副本均生效。
4. **可观测**: `GET /actuator/prometheus` 见 `oryxos_workspace_reloads_total{domain=…}`、`oryxos_leases_acquired_total{kind="index"}`。

## V3 单机档零回归走查

默认配置（cluster 缺省 false）启动：改 `.oryxos/agents/<name>/AGENT.md` → watcher 秒级生效（现状行为）；日志无轮询、无版本号写入。

## V4 NFS 支持矩阵抽查（条件允许时）

按 `docs/SharedVolumeGuide.md` 推荐参数挂 NFS，重复 V2 的 1~3；如实记录卷类型与挂载参数（沿 026 acceptance-report 口径，环境受限时记录机理证据与待补项）。

## SC 对照

SC-001/002/003 → V1 两 IT + V2 走查；SC-004 → V1 原子写；SC-005 → 误配回归；SC-006 → 单机档回归 + poller 频率断言；SC-007 → SharedVolumeGuide + V4。
