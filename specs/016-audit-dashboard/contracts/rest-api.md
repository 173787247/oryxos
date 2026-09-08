# REST API 契约：审计查询接口

统一前缀 `/api/v1`，响应走 `ApiResponse { code, message, data, timestamp }`（成功 `code=0`）。时间窗参数 `from`/`to` 为 ISO-8601 `Instant` 字符串，可空（缺省最近 30 天）。聚合为只读，无写路径。

## 1. LLM 调用汇总（KPI 卡片）

`GET /api/v1/audit/llm/summary?from=&to=`

```json
{
  "code": 0, "message": "success",
  "data": {
    "count": 1234,
    "successCount": 1180,
    "successRate": 0.956,
    "totalPromptTokens": 500000,
    "totalCompletionTokens": 300000,
    "totalCostMicros": 1234567,
    "avgDurationMs": 1820
  }
}
```

- `totalCostMicros`：已计量调用的成本之和（未计量项忽略）；`successRate` 保留小数（前端 `fmtRate` 转百分比）。

## 2. 工具调用汇总（KPI 卡片）

`GET /api/v1/audit/tool/summary?from=&to=`

```json
{
  "code": 0, "message": "success",
  "data": {
    "count": 567,
    "successCount": 540,
    "successRate": 0.952,
    "avgDurationMs": 420
  }
}
```

## 3. 分布分组（图表）

**按模型**：`GET /api/v1/audit/llm/by-model?from=&to=`

**按 provider**：`GET /api/v1/audit/llm/by-provider?from=&to=`

**按工具名**：`GET /api/v1/audit/tool/by-name?from=&to=`

**按 Agent**（LLM + 工具合并）：`GET /api/v1/audit/by-agent?from=&to=`

分组返回统一结构：

```json
{
  "code": 0, "message": "success",
  "data": [
    { "key": "deepseek-chat", "count": 800, "successCount": 780, "totalCostMicros": 900000 },
    { "key": "gpt-5.5", "count": 434, "successCount": 400, "totalCostMicros": 334567 }
  ]
}
```

- 工具分组无 `totalCostMicros` 字段（工具不折算成本）。
- 按 Agent 分组 `key` = `profile_name`；未归属（历史存量行）key = `"(未归属)"`。

## 4. 明细下钻

**LLM 明细**：`GET /api/v1/audit/llm?from=&to=&limit=`

**工具明细**：`GET /api/v1/audit/tool?from=&to=&limit=`

`limit` 缺省 100、上限 500（`@RequestParam(defaultValue="100")`，手动 cap）。

LLM 明细项：

```json
{
  "code": 0, "message": "success",
  "data": [
    {
      "id": 42, "profileName": "ops-agent", "provider": "deepseek",
      "model": "deepseek-chat", "promptTokens": 1200, "completionTokens": 800,
      "totalTokens": 2000, "costMicros": 3600, "success": true,
      "durationMs": 1520, "createdAt": "2026-08-21T03:00:00Z"
    }
  ]
}
```

工具明细项：

```json
{
  "code": 0, "message": "success",
  "data": [
    { "id": 7, "profileName": "ops-agent", "toolName": "read_file",
      "success": true, "durationMs": 12, "createdAt": "2026-08-21T03:00:01Z" }
  ]
}
```

## 5. 价格配置（复用 Provider CRUD）

`PUT /api/v1/providers/{name}` 请求体扩展（既有端点）：

```json
{
  "apiKey": "****abcd", "baseUrl": "https://api.deepseek.com",
  "promptPrice": 1.0, "completionPrice": 2.0
}
```

- `promptPrice`/`completionPrice` 单位「元/百万 token」，可空=未定价；`null` 或省略表示未修改。
- `GET /api/v1/providers/{name}` 返回体含 `promptPrice`/`completionPrice`。
- 价格修改即时生效（`SpringAiProviderServiceImpl` 每次从 `ProviderDef` 读价），无需重启。

// 价格配置接口，请求参数需要带provider+model
