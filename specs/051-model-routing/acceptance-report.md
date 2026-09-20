# Acceptance report: 051-model-routing (#477)

**Date**: 2026-09-20  
**Verdict**: Thin cut covers #477 acceptance; uses #476 cost/pricing signals.

| Acceptance | Evidence |
|------------|----------|
| 路由和 fallback 原因可追溯 | `ModelRoutingServiceTest` + `RoutingApiController` `/decisions`; provider `recordFallback` |
| 策略可灰度并回滚 | `oryxos.routing.enabled=false`; `flagOffIsNoop`; API 404 |
| 不突破权限和数据驻留约束 | `difficultyNoExpansion`; `residencyFilter`; allowlist-only promote |
| Default-off | flag false; no decision log when off |
