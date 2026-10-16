/**
 * 成本看板 Admin HTTP helpers（053）：读 /api/v1/cost/*。
 * oryxos.cost.enabled=false 时接口 404 → CostApiDisabledError（页面转「成本功能未开启」空状态）。
 */

export class CostApiDisabledError extends Error {
  constructor(message = 'cost ledger disabled') {
    super(message)
    this.name = 'CostApiDisabledError'
  }
}

/** params: { runId, taskId, agent, teamId, provider, model, from, to }（均可空）。 */
export async function fetchAttribution(params = {}) {
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v != null && String(v).trim() !== '') qs.set(k, String(v))
  }
  const suffix = qs.toString() ? `?${qs.toString()}` : ''
  return unwrap(await fetch(`/api/v1/cost/attribution${suffix}`))
}

/** 筛选器候选值：{ agents, providers, models, teams }。 */
export async function fetchFacets() {
  return unwrap(await fetch('/api/v1/cost/facets'))
}

/** 按天 LLM 成本序列。params 需含 from/to（ISO Instant），可带维度过滤。 */
export async function fetchDaily(params = {}) {
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v != null && String(v).trim() !== '') qs.set(k, String(v))
  }
  return unwrap(await fetch(`/api/v1/cost/daily?${qs.toString()}`))
}

/** 工具调用归集：按 agent（可选 byTool 再按工具名）的调用次数。 */
export async function fetchToolUsage(params = {}) {
  const qs = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v != null && String(v).trim() !== '') qs.set(k, String(v))
  }
  const suffix = qs.toString() ? `?${qs.toString()}` : ''
  return unwrap(await fetch(`/api/v1/cost/tool-usage${suffix}`))
}

/** 审计 trace 时间线（021）：某一轮消息处理的逐步操作，含工具实际输入摘要。 */
export async function fetchTrace(traceId) {
  return unwrap(await fetch(`/api/v1/audit/trace/${encodeURIComponent(traceId)}`))
}

/** 微元 → 元（数值）。 */
export function microsToYuan(micros) {
  return Number(micros || 0) / 1_000_000
}

/** 微元 → 元字符串，固定 2 位小数（FR-007）。 */
export function formatYuan(micros) {
  return microsToYuan(micros).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

/** LLM 平均延迟（ms）：仅 sourceKind=LLM 的条目（FR-002）。 */
export function avgLlmLatencyMs(entries) {
  const llm = (entries || []).filter((e) => e && e.sourceKind === 'LLM')
  if (llm.length === 0) return 0
  const sum = llm.reduce((acc, e) => acc + Number(e.latencyMs || 0), 0)
  return Math.round(sum / llm.length)
}

async function unwrap(res) {
  let body
  try {
    body = await res.json()
  } catch {
    body = null
  }
  if (res.status === 404) {
    throw new CostApiDisabledError(body?.message || 'cost ledger disabled')
  }
  if (!body || body.code !== 0) {
    throw new Error(body?.message || `请求失败 (${res.status})`)
  }
  return body.data
}
