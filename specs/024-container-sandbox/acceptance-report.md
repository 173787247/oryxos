# 024 容器级执行隔离 · 验收报告（T022）

> 2026-09-15 · 分支 `024-container-sandbox` · 三批交付：US1（#406 已合入）/ US2+US3（#431）
> 验收环境：Windows + Docker Desktop 28.4.0（真机）；CI：ubuntu runner 四项检查全绿

## 一、SC 逐条达成

| # | 验收标准 | 证据 | 结论 |
| --- | --- | --- | --- |
| SC-001 | 未配置 backend 的部署零回归 | 既有测试零修改（tool 288/web 278/cli verify）；真机 local 档端到端（V6） | ✅ |
| SC-002 | docker 档命令在容器内执行、零残留 | 契约 IT 6/6；真机 `ID=alpine`≠宿主；`docker ps -a` 无 alpine 残留 | ✅ |
| SC-003 | 容器内写 /workspace 宿主立即可见 | 契约 IT（`marker.txt` 双向可见） | ✅ |
| SC-004 | 超时终止容器本体无泄漏 | 契约 IT（sleep 600 + destroyForcibly → 轮询容器消失） | ✅ |
| SC-005 | 默认 network=none 出网失败 | 契约 IT（ping 非零退出）+ 状态 API `network: none` | ✅ |
| SC-006 | daemon 停止时清晰报错、进程不崩、恢复自愈 | 真机 V1.1（启动阻断）+ 运行期错误经 ShellTools 既有失败路径回传（FR-011 语义） | ✅ |
| SC-007 | 审计按 backend 筛选、docker 记录含容器 ID | 真机 V4；containerId 修复后实录 `a688d916…` | ✅ |
| SC-008 | 按 Agent 覆写各行其道 | 真机 V3（alpine vs Windows 宿主，审计 ground truth） | ✅ |

## 二、实测数据

| 项 | 数值 |
| --- | --- |
| 契约测试（真机 daemon） | 6/6 通过，~11s |
| 单元测试增量 | 024 新增约 60 用例（tool 288 / web 278 / core 39 / cli 27 模块全绿） |
| docker 档单次执行开销 | 容器启动 + CLI ≈ 数百 ms 量级（Agent 往返由 LLM 主导，无可感知劣化） |
| local 档开销 | 零（无探测线程、无上下文置入、审计走既有 8 参签名） |

## 三、过程中发现并修复的缺陷（真机验收的价值记录）

1. **containerId 竞态**（V7）：onExit 删除 cidfile 与审计惰性读取竞态 → 先捕获后清理。**单测层无法暴露**（时序类缺陷），由真机端到端发现。
2. **装配循环依赖**（CI E2E 发现）：`toolRegistry → profileRegistry → agentLoader → tools → toolRegistry` → ObjectProvider 惰性解析。
3. **controller 构造器歧义**（CI E2E 发现）：双构造器无 @Autowired 提示致 Spring 退找默认构造器。
4. **web 模块五处 SpotBugs**（CI 发现）：SPRING_ENDPOINT/EI/COMMAND_INJECTION/CRLF，按仓库先例修复。

方法论沉淀：模块级单测 → 模块级 verify → **boot E2E（全上下文）** → CI → **真机走查**，五层各抓不同类缺陷——2/3 类只有后两层能暴露。

## 四、与裁决的偏差

无。5 处 RQ 均按推荐实施（#362 合入即基线）；T019/T020 文档项随本报告与 PR 描述交付，website 独立页待维护者指示是否单独补。
