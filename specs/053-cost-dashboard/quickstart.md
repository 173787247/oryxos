# Quickstart: 成本看板

**Feature**: 053-cost-dashboard

验证成本看板端到端可用。接口细节见 [contracts/cost-api.md](contracts/cost-api.md)，字段见 [data-model.md](data-model.md)。

## 前置

- 完成 `/speckit-implement`（后端接口 + 前端页 + RBAC 登记）
- 配置 `oryxos.cost.enabled=true`，账本中已有成本记录（或造数）
- 前端已构建（`frontend` → `static/admin`），随 jar 启动

## 场景 1：总览与维度下钻

```bash
curl -s "http://localhost:8099/api/v1/cost/attribution?from=2026-10-07T00:00:00Z&to=2026-10-14T00:00:00Z" | jq .data
```
**预期**：返回 totalCostMicros、llmCostMicros、`llmCallCount`、`toolCallCount`、totalLatencyMs 等；加 `&agent=ops` 后数值只反映该 agent。对应 US1/US2、CT-2。

## 场景 2：趋势折线（按天 + 补零）

```bash
curl -s "http://localhost:8099/api/v1/cost/daily?from=2026-10-07T00:00:00Z&to=2026-10-14T00:00:00Z" | jq .data
```
**预期**：返回每日 LLM 成本点，无成本的日期为 0；跨度 >92 天返回参数错误而非 500。对应 US3、CT-3。

## 场景 3：工具调用归集

```bash
curl -s "http://localhost:8099/api/v1/cost/tool-usage" | jq .data
```
**预期**：按 agent 的工具调用次数；各项之和 = 范围内 TOOL 条目总数。对应 US4、SC-004、CT-4。

## 场景 4：筛选器候选值

```bash
curl -s "http://localhost:8099/api/v1/cost/facets" | jq .data
```
**预期**：agents/providers/models/teams 去重列表，无 run/task。对应 FR-013、CT-5。

## 场景 5：功能未开启空状态

将 `oryxos.cost.enabled=false` 重启后：
```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8099/api/v1/cost/attribution
```
**预期**：404；页面显示「成本功能未开启」而非白屏/错误堆栈。对应 FR-008、CT-1、SC-005。

## 场景 6：页面与菜单（手动）

启动后浏览器打开 `http://localhost:8099/admin/` → 导航出现「成本」项 → 进入后 KPI/趋势/工具调用/明细齐全，金额为元 2 位，默认近 7 天、明细按时间倒序。对应 FR-001/002/006。

## 场景 7：RBAC（若启用）

`oryxos.web.rbac.enabled=true` 下，VIEWER 账号可访问 `/api/v1/cost/**`（200），无角色匿名被拒（403 非 500）。对应 FR-014、CT-6。

## 通过标准

- 7 个场景符合预期
- 后端新增/改动模块 `mvn verify` 门禁全绿（Spotless/PMD/SpotBugs/测试）
- 既有成本相关测试不回归
