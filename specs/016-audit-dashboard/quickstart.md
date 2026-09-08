# Quickstart: 审计查询接口与报表看板

端到端验证指南。实现细节见 [tasks.md](tasks.md)（/speckit-tasks 产出），契约见 [contracts/rest-api.md](contracts/rest-api.md)。

## 前置

```bash
# 全量门禁（编译 + 静态检查 + 测试）
mvn clean verify

# 启动服务（mock provider 免 key）
java -jar oryxos-boot/target/*.jar serve
# 或开发态
mvn -pl oryxos-boot spring-boot:run
```

管理台：`http://localhost:8080/admin/`（若开启 auth 需先登录）。

## 验证场景

### S1. 配置单价

1. 管理台 → Provider 列表 → 编辑某 provider，填 `promptPrice=1.0`、`completionPrice=2.0`，保存。
2. `curl http://localhost:8080/api/v1/providers/<name>` → 返回体含 `promptPrice`/`completionPrice`。

### S2. 触发一次调用并验证成本落库

1. 用 mock provider 走一次对话（或定时任务触发）。
2. 查库验证：
   ```bash
   sqlite3 oryxos.db "SELECT provider, model, prompt_tokens, completion_tokens, cost_micros, profile_name FROM llm_calls ORDER BY id DESC LIMIT 1;"
   ```
   预期：`cost_micros = round(prompt_tokens × promptPrice + completion_tokens × completionPrice)`，`profile_name` 为发起 Agent 名。

### S3. 聚合查询

```bash
curl 'http://localhost:8080/api/v1/audit/llm/summary'            # LLM 汇总
curl 'http://localhost:8080/api/v1/audit/tool/summary'           # 工具汇总
curl 'http://localhost:8080/api/v1/audit/llm/by-model'           # 按模型分布
curl 'http://localhost:8080/api/v1/audit/tool/by-name'           # 按工具分布
curl 'http://localhost:8080/api/v1/audit/by-agent'               # 按 Agent 分布
curl 'http://localhost:8080/api/v1/audit/llm?limit=5'            # LLM 明细
```

预期：返回 `code=0`，数据与库里记录一致；成本为未计量（未定价/失败）时不出现在总额、明细显示 null。

### S4. 看板页

管理台 → 「报表」页：KPI 卡片（次数/成本/成功率/耗时）、分布图表、明细表格、时间窗切换（近7天/30天/全部）正常。

### S5. 存量迁移

1. 用旧的 `oryxos.db`（无新列）启动服务。
2. 预期：升级器自动补 5 列，启动不崩；历史行 `cost_micros`/`profile_name` 为 null，看板显示「未计量 / 未归属」。

## 自动化测试入口

- 成本换算单测：`CostComputeTest`（1元/百万×1000 token == 1000 微元；null 分支）
- 聚合服务单测：`AuditMetricsServiceTest`（预置数据断言次数/成功率/耗时/成本）
- 迁移测试：`AuditSchemaUpgradeTest`（存量库补列幂等）
- 契约/回归：`ProviderApiControllerTest`（价格 CRUD 透传）、既有审计测试不回归

全量：`mvn clean verify`
