# Quickstart: 记忆检索升级验收路径

**Date**: 2026-08-20　**Feature**: [spec.md](spec.md)

## A. 未配置向量化：行为零变化（US2 / SC-002/003）

```bash
# config/application.yml 不配 embedding.*（或留空）→ 启动
# 1) 让 Agent 记几条（含大小写混合的代号）
#    对话：「记住：工单 OPS-4721 已升级到二线」
# 2) recall 验证：结果与升级前一致；「ops-4721」也能命中（唯一例外：大小写统一）
# 3) 日志无告警刷屏；审计里该调用照常落 tool_invocations
```

## B. mock 全链路（US4 / SC-004/005）

```bash
# embedding.provider=mock → 重启
# 写入若干归档记忆 → recall：三路融合生效、同输入恒同输出（CI 断言同款）
# 断言 memory_vectors 有行：dim=64、embedding_model=mock/deterministic
```

## C. 真实语义 + 时间路（US1 / SC-001/004）

```bash
# embedding.provider=zhipu, embedding.model=embedding-3 → 重启（存量记忆 30s 内补索引，SC-006）
# 1) 记「发布流程在灰度环节踩雷，回滚后改为分批放量」
# 2) 隔日问「上次那个部署的坑怎么处理的」→ 语义命中（无共同关键词）
# 3) 构造两条相关性相当、时间不同的条目 → 较新者排前（时间路）
# 4) 权重调优演示：memory.recall.weight.recency=2.0 → 重启 → 排序变化
```

## D. 降级与零丢失（US2 场景 4 / SC-007）

```bash
# embedding.provider 配成不可达 provider → recall 仍返回（关键词+时间），尾行降级标注；
# 同期 save_memory 100% 落库成功；恢复配置重启 → 对账补齐索引，语义路回归
```

## E. sqlite 档与作用域（FR-014）

```bash
# memory.backend=sqlite → 老库启动：MemorySchemaUpgrade 幂等补 agent_name 列，
# 启动日志一次性迁移说明；两个 Agent 各记各查，绝不串（US1 场景 6）
```

## F. mem0 档跑通（US5 / SC-011，@Tag integration）

```bash
docker compose -f docker/mem0/compose.yaml up -d      # mem0 OSS（LLM/embedder 走 zhipu，env 注入）
# memory.backend=mem0 + memory.mem0.* → 重启
# 1) save core「我叫小林，永远用中文回复」→ mem0 中原文逐字（infer:false）；每轮注入完整出现
# 2) save archival 若干 → recall 语义命中（mem0 提炼后）
# 3) 分区过滤实测：core 不进检索结果、archival 不混核心注入（#3773 版本核验）
# 4) docker stop mem0 → recall 可读错误入审计、对话不中断；save 失败可读呈现
# 5) 契约参数化套件挂 mem0 档全绿：mvn -pl oryxos-memory test -Dgroups=integration -DexcludedGroups=
```

## G. 契约与回归（SC-008/009/010）

```bash
mvn verify   # 全 reactor：四档契约测试（3 档常驻 + 桩含分区坏桩装配拒绝负例）、兼容性专项、存量测试零回归
# 性能抽查（SC-008）：脚本灌万条归档记忆（mock embedding）→ 单次 recall 计时 ≤1s（验收环境实测，T029 勾验）
```
