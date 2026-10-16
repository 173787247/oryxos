<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  CostApiDisabledError,
  avgLlmLatencyMs,
  fetchAttribution,
  fetchDaily,
  fetchFacets,
  fetchToolUsage,
  fetchTrace,
  formatYuan,
} from './cost-api.js'

const state = ref({ loading: true, error: null, disabled: false, data: null })
const facets = ref({ agents: [], providers: [], models: [], teams: [] })
const filters = reactive({ agent: '', provider: '', model: '', teamId: '', runId: '', taskId: '' })
const trend = ref({ loading: true, error: null, points: [] })
const toolUsage = ref({ loading: true, error: null, rows: [] })
const toolUsageMax = computed(() =>
  Math.max(1, ...(toolUsage.value.rows || []).map((r) => Number(r.callCount || 0))),
)

function isoDate(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const today = new Date()
const weekAgo = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 6)
const range = reactive({ from: isoDate(weekAgo), to: isoDate(today) })

/** 日期（本地）→ ISO Instant；from 取当日 0 点，to 取次日 0 点（不含）。 */
function rangeParams() {
  const [fy, fm, fd] = range.from.split('-').map(Number)
  const [ty, tm, td] = range.to.split('-').map(Number)
  const from = new Date(fy, fm - 1, fd, 0, 0, 0, 0).toISOString()
  const to = new Date(ty, tm - 1, td + 1, 0, 0, 0, 0).toISOString()
  return { from, to }
}

function activeFilters() {
  const f = {}
  for (const [k, v] of Object.entries(filters)) {
    if (v != null && String(v).trim() !== '') f[k] = String(v).trim()
  }
  return f
}

function resetFilters() {
  filters.agent = ''
  filters.provider = ''
  filters.model = ''
  filters.teamId = ''
  filters.runId = ''
  filters.taskId = ''
  reloadAll()
}

const kpis = computed(() => {
  const d = state.value.data
  if (!d) return null
  return {
    totalCost: formatYuan(d.totalCostMicros),
    llmCost: formatYuan(d.llmCostMicros),
    toolCost: formatYuan(d.toolCostMicros),
    totalTokens: Number(d.promptTokens || 0) + Number(d.completionTokens || 0),
    llmCalls: Number(d.llmCallCount || 0),
    toolCalls: Number(d.toolCallCount || 0),
    avgLatency: avgLlmLatencyMs(d.entries),
  }
})

const isEmpty = computed(() => {
  const d = state.value.data
  return d && Number(d.llmCallCount || 0) === 0 && Number(d.toolCallCount || 0) === 0
})

// 明细表：分页 + 排序（默认时间倒序）
const PAGE_SIZE = 50
const detail = reactive({ page: 1, sortKey: 'createdAt', sortDir: 'desc' })
function entryCostMicros(e) {
  return Number(e.llmCostMicros || 0) + Number(e.toolCostMicros || 0)
}
// 明细按 session 聚合：一个 session 一行，展开看该 session 下的逐次操作。
// 全部从 attribution 的 entries 前端算，不另调后端。
const expanded = reactive({})
function toggleSession(sid) {
  expanded[sid] = !expanded[sid]
}
// 每轮(trace)操作：默认收起，点开才展；展开时懒加载审计 trace（含工具实际输入）
const traceOpen = reactive({})
const traceSteps = reactive({})
function toggleTrace(tid) {
  traceOpen[tid] = !traceOpen[tid]
  if (traceOpen[tid] && !traceSteps[tid]) loadTraceSteps(tid)
}
async function loadTraceSteps(tid) {
  traceSteps[tid] = { loading: true, error: null, steps: [] }
  try {
    const d = await fetchTrace(tid)
    traceSteps[tid] = { loading: false, error: null, steps: (d && d.steps) || [] }
  } catch (e) {
    traceSteps[tid] = { loading: false, error: e.message, steps: [] }
  }
}
/** 把一步的工具输入摘要格式化成人读：shell 拼成命令行，其它工具显示紧凑摘要。 */
function opInput(st) {
  const s = st.inputSummary
  if (!s) return st.type === 'LLM' ? '模型推理' : '—'
  try {
    const o = JSON.parse(s)
    if (o.executable) return [o.executable, ...(o.arguments || [])].join(' ')
  } catch {
    /* 非 JSON，原样截断 */
  }
  return s.length > 160 ? s.slice(0, 160) + '…' : s
}
const sessionRows = computed(() => {
  const entries = (state.value.data && state.value.data.entries) || []
  const map = new Map()
  for (const e of entries) {
    const sid = e.sessionId || '(无 session)'
    let g = map.get(sid)
    if (!g) {
      g = { sessionId: sid, entries: [], costMicros: 0, llmCalls: 0, toolCalls: 0, tokens: 0, first: '', last: '' }
      map.set(sid, g)
    }
    g.entries.push(e)
    g.costMicros += entryCostMicros(e)
    if (e.sourceKind === 'LLM') g.llmCalls++
    else if (e.sourceKind === 'TOOL') g.toolCalls++
    g.tokens += Number(e.totalTokens || 0)
    const t = e.createdAt || ''
    if (t && (!g.first || t < g.first)) g.first = t
    if (t && (!g.last || t > g.last)) g.last = t
  }
  // 每个 session 内再按 trace（每轮消息处理）分组，trace 内操作按时间正序
  for (const g of map.values()) {
    const tmap = new Map()
    g.entries.sort((a, b) => (a.createdAt || '').localeCompare(b.createdAt || ''))
    for (const e of g.entries) {
      const tid = e.traceId || '(无 trace)'
      let t = tmap.get(tid)
      if (!t) {
        t = { traceId: tid, entries: [], costMicros: 0, llmCalls: 0, toolCalls: 0, tokens: 0, first: '', last: '' }
        tmap.set(tid, t)
      }
      t.entries.push(e)
      t.costMicros += entryCostMicros(e)
      if (e.sourceKind === 'LLM') t.llmCalls++
      else if (e.sourceKind === 'TOOL') t.toolCalls++
      t.tokens += Number(e.totalTokens || 0)
      const ct = e.createdAt || ''
      if (ct && (!t.first || ct < t.first)) t.first = ct
      if (ct && (!t.last || ct > t.last)) t.last = ct
    }
    // 轮次号按时间正序分配（第1轮=最早），但展示时倒序（最新一轮在上）
    const chrono = [...tmap.values()].sort((a, b) => (a.first || '').localeCompare(b.first || ''))
    chrono.forEach((t, i) => {
      t.roundNo = i + 1
    })
    g.traces = chrono.reverse()
  }
  const rows = [...map.values()]
  const dir = detail.sortDir === 'asc' ? 1 : -1
  rows.sort((a, b) => {
    const av = detail.sortKey === 'cost' ? a.costMicros : a.last
    const bv = detail.sortKey === 'cost' ? b.costMicros : b.last
    return av < bv ? -dir : av > bv ? dir : 0
  })
  return rows
})
const totalPages = computed(() => Math.max(1, Math.ceil(sessionRows.value.length / PAGE_SIZE)))
const pagedSessions = computed(() =>
  sessionRows.value.slice((detail.page - 1) * PAGE_SIZE, detail.page * PAGE_SIZE),
)
function sortBy(key) {
  if (detail.sortKey === key) {
    detail.sortDir = detail.sortDir === 'asc' ? 'desc' : 'asc'
  } else {
    detail.sortKey = key
    detail.sortDir = 'desc'
  }
  detail.page = 1
}
function fmtTime(t) {
  return t ? String(t).slice(0, 19).replace('T', ' ') : '—'
}
function opLabel(e) {
  if (e.sourceKind === 'TOOL') return '工具:' + (e.sourceRef || '')
  return e.model || (e.provider ? e.provider : 'LLM')
}
/** 单元格展示元（2 位）+ 原始精确值做悬浮提示（FR-007 小额精度）。 */
function costCell(micros) {
  return { text: `¥${formatYuan(micros)}`, title: `${Number(micros || 0) / 1_000_000} 元（${micros} 微元）` }
}

async function load() {
  // 重载时保留旧数据，仅置 loading，避免整页闪烁（旧数据在位 → 新数据到达再替换）
  state.value = { ...state.value, loading: true, error: null }
  try {
    const data = await fetchAttribution({ ...activeFilters(), ...rangeParams() })
    state.value = { loading: false, error: null, disabled: false, data }
  } catch (e) {
    if (e instanceof CostApiDisabledError) {
      state.value = { loading: false, error: null, disabled: true, data: null }
      return
    }
    state.value = { ...state.value, loading: false, error: e.message }
  }
}

async function loadTrend() {
  trend.value = { ...trend.value, loading: true, error: null }
  try {
    const points = await fetchDaily({ ...activeFilters(), ...rangeParams() })
    trend.value = { loading: false, error: null, points: points || [] }
  } catch (e) {
    if (e instanceof CostApiDisabledError) {
      trend.value = { loading: false, error: null, points: [] }
      return
    }
    trend.value = { ...trend.value, loading: false, error: e.message }
  }
}

async function loadToolUsage() {
  toolUsage.value = { ...toolUsage.value, loading: true, error: null }
  try {
    const rows = await fetchToolUsage({ ...activeFilters(), ...rangeParams() })
    toolUsage.value = { loading: false, error: null, rows: rows || [] }
  } catch (e) {
    if (e instanceof CostApiDisabledError) {
      toolUsage.value = { loading: false, error: null, rows: [] }
      return
    }
    toolUsage.value = { ...toolUsage.value, loading: false, error: e.message }
  }
}

function reloadAll() {
  // 筛选/时间范围一变就回到第 1 页：停在第 3 页时改条件会切出一段空片，而 totalPages 已经变小，
  // 页面就成了「表头在、行空、页码显示 3 / 1」——空态提示又用的是未分页的 sessionRows，也不会出现。
  detail.page = 1
  load()
  loadTrend()
  loadToolUsage()
}

async function loadFacets() {
  try {
    facets.value = await fetchFacets()
  } catch {
    // facets 非关键：未开启/失败时下拉为空，不阻断页面
    facets.value = { agents: [], providers: [], models: [], teams: [] }
  }
}

onMounted(() => {
  loadFacets()
  reloadAll()
})

// 趋势 SVG 几何：等宽折线 + 填充，自适应视图框 720x160。
const CHART = { w: 720, h: 160, pad: 8 }
const trendGeom = computed(() => {
  const pts = trend.value.points || []
  if (pts.length === 0) return null
  const vals = pts.map((p) => Number(p.llmCostMicros || 0))
  const max = Math.max(1, ...vals)
  const n = pts.length
  const innerW = CHART.w - CHART.pad * 2
  const innerH = CHART.h - CHART.pad * 2
  const x = (i) => CHART.pad + (n === 1 ? innerW / 2 : (innerW * i) / (n - 1))
  const y = (v) => CHART.pad + innerH - (innerH * v) / max
  const line = pts.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(vals[i]).toFixed(1)}`).join(' ')
  const area = `${line} L${x(n - 1).toFixed(1)},${(CHART.h - CHART.pad).toFixed(1)} L${x(0).toFixed(1)},${(CHART.h - CHART.pad).toFixed(1)} Z`
  return { line, area, maxYuan: (max / 1_000_000).toFixed(2), first: pts[0].date, last: pts[n - 1].date }
})
defineExpose({ reload: reloadAll })
</script>

<template>
  <div class="cost">
    <p v-if="state.loading && !state.data" class="hint">加载中…</p>

    <div v-else-if="state.disabled" class="empty">
      <p class="empty-title">成本功能未开启</p>
      <p class="hint">
        当前实例未启用成本账本。请在配置中设置
        <code>oryxos.cost.enabled=true</code> 并重启后再查看。
      </p>
    </div>

    <p v-else-if="state.error && !state.data" class="err">加载失败：{{ state.error }}</p>

    <template v-else-if="kpis">
      <div class="filters">
        <label>Agent
          <select v-model="filters.agent" @change="reloadAll">
            <option value="">全部</option>
            <option v-for="a in facets.agents" :key="a" :value="a">{{ a }}</option>
          </select>
        </label>
        <label>Provider
          <select v-model="filters.provider" @change="reloadAll">
            <option value="">全部</option>
            <option v-for="p in facets.providers" :key="p" :value="p">{{ p }}</option>
          </select>
        </label>
        <label>Model
          <select v-model="filters.model" @change="reloadAll">
            <option value="">全部</option>
            <option v-for="m in facets.models" :key="m" :value="m">{{ m }}</option>
          </select>
        </label>
        <label>Team
          <select v-model="filters.teamId" @change="reloadAll">
            <option value="">全部</option>
            <option v-for="t in facets.teams" :key="t" :value="t">{{ t }}</option>
          </select>
        </label>
        <label>Run ID
          <input v-model.trim="filters.runId" placeholder="run id" @keyup.enter="reloadAll" />
        </label>
        <label>Task ID
          <input v-model.trim="filters.taskId" placeholder="task id" @keyup.enter="reloadAll" />
        </label>
        <label>起
          <input type="date" v-model="range.from" @change="reloadAll" />
        </label>
        <label>止
          <input type="date" v-model="range.to" @change="reloadAll" />
        </label>
        <button class="btn" @click="reloadAll">应用</button>
        <button class="btn" @click="resetFilters">重置</button>
      </div>
      <p class="scope-note">口径：{{ range.from }} ~ {{ range.to }}（默认近 7 天）。日期范围上限约 92 天。</p>
      <div class="kpi-grid">
        <div class="kpi"><span class="kpi-label">总成本</span><span class="kpi-val">¥{{ kpis.totalCost }}</span></div>
        <div class="kpi"><span class="kpi-label">LLM 成本</span><span class="kpi-val">¥{{ kpis.llmCost }}</span></div>
        <div class="kpi"><span class="kpi-label">工具成本</span><span class="kpi-val">¥{{ kpis.toolCost }}</span></div>
        <div class="kpi"><span class="kpi-label">总 Token</span><span class="kpi-val">{{ kpis.totalTokens.toLocaleString() }}</span></div>
        <div class="kpi"><span class="kpi-label">LLM 调用数</span><span class="kpi-val">{{ kpis.llmCalls.toLocaleString() }}</span></div>
        <div class="kpi"><span class="kpi-label">工具调用数</span><span class="kpi-val">{{ kpis.toolCalls.toLocaleString() }}</span></div>
        <div class="kpi"><span class="kpi-label">平均延迟 (LLM)</span><span class="kpi-val">{{ kpis.avgLatency.toLocaleString() }} ms</span></div>
      </div>
      <section class="trend">
        <h4>每日 LLM 成本趋势</h4>
        <p v-if="trend.loading && trend.points.length === 0" class="hint">加载趋势…</p>
        <p v-else-if="trend.error" class="err">趋势加载失败：{{ trend.error }}</p>
        <template v-else-if="trendGeom">
          <svg class="trend-svg" :viewBox="`0 0 ${CHART.w} ${CHART.h}`" preserveAspectRatio="none">
            <path :d="trendGeom.area" class="trend-area" />
            <path :d="trendGeom.line" class="trend-line" />
          </svg>
          <div class="trend-axis">
            <span>{{ trendGeom.first }}</span>
            <span>峰值 ¥{{ trendGeom.maxYuan }}</span>
            <span>{{ trendGeom.last }}</span>
          </div>
        </template>
        <p v-else class="hint">该范围无趋势数据。</p>
      </section>
      <section class="tool-usage">
        <h4>工具调用（按 Agent）</h4>
        <p v-if="toolUsage.loading && toolUsage.rows.length === 0" class="hint">加载工具调用…</p>
        <p v-else-if="toolUsage.error" class="err">加载失败：{{ toolUsage.error }}</p>
        <p v-else-if="toolUsage.rows.length === 0" class="hint">该范围无工具调用。</p>
        <ul v-else class="tu-list">
          <li v-for="(r, i) in toolUsage.rows" :key="i" class="tu-row">
            <span class="tu-agent">{{ r.agentName || '—' }}</span>
            <span class="tu-bar"><span class="tu-bar-fill" :style="{ width: (r.callCount / toolUsageMax * 100) + '%' }"></span></span>
            <span class="tu-count">{{ r.callCount }}</span>
          </li>
        </ul>
      </section>
      <section class="detail">
        <h4>成本明细（按 Session）</h4>
        <p v-if="sessionRows.length === 0" class="hint">当前范围暂无流水。</p>
        <template v-else>
          <table class="dt">
            <thead>
              <tr>
                <th class="w-ex"></th>
                <th>Session</th>
                <th class="sortable" @click="sortBy('createdAt')">
                  起止时间<span v-if="detail.sortKey === 'createdAt'">{{ detail.sortDir === 'asc' ? ' ▲' : ' ▼' }}</span>
                </th>
                <th class="num">操作数</th>
                <th class="num">Token</th>
                <th class="num sortable" @click="sortBy('cost')">
                  成本<span v-if="detail.sortKey === 'cost'">{{ detail.sortDir === 'asc' ? ' ▲' : ' ▼' }}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              <template v-for="s in pagedSessions" :key="s.sessionId">
                <tr class="sess-row" @click="toggleSession(s.sessionId)">
                  <td class="w-ex">{{ expanded[s.sessionId] ? '▾' : '▸' }}</td>
                  <td class="mono sess-id">{{ s.sessionId }}</td>
                  <td class="mono">{{ fmtTime(s.first) }}<template v-if="s.last !== s.first"> ~ {{ fmtTime(s.last) }}</template></td>
                  <td class="num">{{ s.traces.length }} 轮 · {{ s.llmCalls }} LLM / {{ s.toolCalls }} 工具</td>
                  <td class="num">{{ s.tokens.toLocaleString() }}</td>
                  <td class="num" :title="costCell(s.costMicros).title">{{ costCell(s.costMicros).text }}</td>
                </tr>
                <tr v-if="expanded[s.sessionId]" class="ops-row">
                  <td></td>
                  <td colspan="5">
                    <div v-for="t in s.traces" :key="t.traceId" class="trace-grp">
                      <div class="trace-head" @click="toggleTrace(t.traceId)">
                        <span class="tr-ex">{{ traceOpen[t.traceId] ? '▾' : '▸' }}</span>
                        <span class="trace-badge">第 {{ t.roundNo }} 轮</span>
                        <span class="trace-meta">{{ fmtTime(t.first) }} · {{ t.llmCalls }} LLM / {{ t.toolCalls }} 工具 · {{ t.tokens.toLocaleString() }} token · {{ costCell(t.costMicros).text }}</span>
                        <span class="mono trace-tid">{{ String(t.traceId).slice(0, 8) }}</span>
                      </div>
                      <div v-if="traceOpen[t.traceId]" class="trace-ops">
                        <p v-if="!traceSteps[t.traceId] || traceSteps[t.traceId].loading" class="hint">加载操作…</p>
                        <p v-else-if="traceSteps[t.traceId].error" class="err">{{ traceSteps[t.traceId].error }}</p>
                        <table v-else class="ops">
                          <thead>
                            <tr><th>时间</th><th>操作</th><th>工具输入 / 说明</th><th class="num">Token</th><th class="num">成本</th><th class="num">延迟(ms)</th></tr>
                          </thead>
                          <tbody>
                            <tr v-for="(st, i) in traceSteps[t.traceId].steps" :key="i">
                              <td class="mono">{{ fmtTime(st.at) }}</td>
                              <td><span :class="['tag', st.type === 'TOOL' ? 'tag-tool' : 'tag-llm']">{{ st.type }}</span> {{ st.name }}</td>
                              <td class="mono op-input" :title="opInput(st)">{{ opInput(st) }}</td>
                              <td class="num">{{ Number(st.totalTokens || 0).toLocaleString() }}</td>
                              <td class="num" :title="costCell(st.costMicros || 0).title">{{ costCell(st.costMicros || 0).text }}</td>
                              <td class="num">{{ Number(st.durationMs || 0).toLocaleString() }}</td>
                            </tr>
                          </tbody>
                        </table>
                      </div>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
          <div class="dt-page">
            <button class="btn" :disabled="detail.page <= 1" @click="detail.page--">上一页</button>
            <span>{{ detail.page }} / {{ totalPages }}</span>
            <button class="btn" :disabled="detail.page >= totalPages" @click="detail.page++">下一页</button>
          </div>
        </template>
      </section>
      <p v-if="isEmpty" class="hint">当前范围暂无成本数据。</p>
    </template>
  </div>
</template>

<style scoped>
.cost-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.cost-head h3 { margin: 0; color: var(--text-1); }
.btn { border: 1px solid var(--border); background: var(--bg-mute); color: var(--text-1); border-radius: 8px; padding: 4px 12px; cursor: pointer; }
.btn:hover:not(:disabled) { border-color: var(--brand); color: var(--brand); }
.btn:disabled { opacity: 0.5; cursor: default; }
.filters { display: flex; flex-wrap: wrap; gap: 10px; align-items: flex-end; margin-bottom: 12px; }
.filters label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-2); }
.filters select, .filters input { border: 1px solid var(--border); background: var(--bg-mute); color: var(--text-1); border-radius: 8px; padding: 5px 8px; min-width: 120px; }
.scope-note { color: var(--text-2); font-size: 13px; margin: 0 0 12px; }
.kpi-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 12px; }
.kpi { display: flex; flex-direction: column; gap: 6px; padding: 14px 16px; border: 1px solid var(--border); border-radius: 12px; background: var(--bg-elev); }
.kpi-label { color: var(--text-2); font-size: 13px; }
.kpi-val { color: var(--text-1); font-size: 22px; font-weight: 600; }
.trend { margin-top: 20px; }
.trend h4 { margin: 0 0 8px; color: var(--text-1); font-size: 15px; }
.trend-svg { width: 100%; height: 160px; display: block; border: 1px solid var(--border); border-radius: 12px; background: var(--bg-elev); }
.trend-line { fill: none; stroke: var(--brand); stroke-width: 2; vector-effect: non-scaling-stroke; }
.trend-area { fill: var(--brand-soft, rgba(255,140,0,0.12)); stroke: none; }
.trend-axis { display: flex; justify-content: space-between; color: var(--text-2); font-size: 12px; margin-top: 6px; }
.tool-usage { margin-top: 20px; }
.tool-usage h4 { margin: 0 0 8px; color: var(--text-1); font-size: 15px; }
.tu-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.tu-row { display: grid; grid-template-columns: 140px 1fr 48px; align-items: center; gap: 10px; }
.tu-agent { color: var(--text-1); font-size: 13px; }
.tu-bar { height: 14px; background: var(--bg-mute); border-radius: 7px; overflow: hidden; }
.tu-bar-fill { display: block; height: 100%; background: var(--brand); }
.tu-count { text-align: right; color: var(--text-2); font-size: 13px; }
.detail { margin-top: 20px; }
.detail h4 { margin: 0 0 8px; color: var(--text-1); font-size: 15px; }
.dt { width: 100%; border-collapse: collapse; font-size: 13px; }
.dt th, .dt td { text-align: left; padding: 6px 10px; border-bottom: 1px solid var(--border); color: var(--text-1); }
.dt th { color: var(--text-2); font-weight: 600; }
.dt th.sortable { cursor: pointer; user-select: none; }
.dt th.sortable:hover { color: var(--brand); }
.dt .num { text-align: right; }
.dt .mono { font-family: var(--mono, ui-monospace, monospace); font-size: 12px; }
.dt .trace { max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dt .w-ex { width: 24px; text-align: center; color: var(--text-2); }
.dt .sess-row { cursor: pointer; }
.dt .sess-row:hover { background: var(--bg-mute); }
.dt .sess-id { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ops { width: 100%; border-collapse: collapse; font-size: 12px; margin: 4px 0 8px; background: var(--bg-mute); border-radius: 8px; }
.ops th, .ops td { text-align: left; padding: 4px 8px; border-bottom: 1px solid var(--border); }
.ops th { color: var(--text-2); font-weight: 500; }
.ops .num { text-align: right; }
.tag { display: inline-block; padding: 0 6px; border-radius: 6px; font-size: 11px; line-height: 16px; margin-right: 4px; }
.tag-llm { background: var(--brand-soft, rgba(255,140,0,0.18)); color: var(--brand); }
.tag-tool { background: rgba(120,120,120,0.22); color: var(--text-2); }
.ops-row > td { padding: 4px 10px 8px 34px; }
.trace-grp { margin-bottom: 8px; border: 1px solid var(--border); border-radius: 8px; padding: 6px 10px; }
.trace-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 2px 0; font-size: 12px; cursor: pointer; }
.trace-head:hover { color: var(--brand); }
.tr-ex { width: 14px; color: var(--text-2); }
.trace-badge { background: var(--brand); color: #000; border-radius: 6px; padding: 0 8px; line-height: 18px; font-weight: 600; }
.trace-tid { color: var(--text-3, var(--text-2)); margin-left: auto; opacity: 0.6; }
.trace-meta { color: var(--text-2); }
.trace-ops { margin-top: 6px; }
.op-input { max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-1); }
.dt-page { display: flex; align-items: center; gap: 12px; margin-top: 10px; color: var(--text-2); font-size: 13px; }
.empty { padding: 24px; border: 1px dashed var(--border); border-radius: 12px; }
.empty-title { color: var(--text-1); font-weight: 600; margin: 0 0 6px; }
.hint { color: var(--text-2); font-size: 14px; }
.err { color: var(--danger, #e5484d); }
code { background: var(--bg-mute); padding: 1px 6px; border-radius: 4px; }
</style>
