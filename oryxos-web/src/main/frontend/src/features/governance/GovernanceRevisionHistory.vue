<script setup>
import { computed, reactive, ref, watch } from 'vue'
import {
  VersionHistoryDisabledError,
  diffRevisions,
  listRevisions,
  restoreRevision,
} from './governance-revisions-api.js'

const props = defineProps({
  /** 'agents' | 'skills' | 'knowledge' | 'channels' */
  apiKind: { type: String, required: true },
  name: { type: String, required: true },
})

const emit = defineEmits(['restored'])

const state = reactive({
  loading: false,
  disabled: false,
  error: '',
  rows: [],
  workspaceRevision: null,
})

const pickA = ref('')
const pickB = ref('')
const diff = reactive({ loading: false, error: '', text: '', fromLabel: '', toLabel: '' })
const restoreBusy = ref(null)

const canDiff = computed(
  () =>
    pickA.value !== '' &&
    pickB.value !== '' &&
    pickA.value !== pickB.value &&
    !diff.loading &&
    !state.disabled,
)

function formatWhen(iso) {
  if (!iso) return '—'
  try {
    return new Date(iso).toLocaleString()
  } catch {
    return String(iso)
  }
}

function rowLabel(row) {
  const ver = row.versionLabel || '—'
  return `#${row.id} · ${ver} · ${row.actor || '—'} · ${formatWhen(row.createdAt)}`
}

async function load() {
  if (!props.name) return
  state.loading = true
  state.error = ''
  state.disabled = false
  state.workspaceRevision = null
  pickA.value = ''
  pickB.value = ''
  diff.text = ''
  diff.error = ''
  diff.fromLabel = ''
  diff.toLabel = ''
  try {
    const data = await listRevisions(props.apiKind, props.name, value => { state.workspaceRevision = value })
    state.rows = Array.isArray(data) ? data : []
  } catch (e) {
    state.rows = []
    if (e instanceof VersionHistoryDisabledError) {
      state.disabled = true
      state.error = ''
    } else {
      state.error = e.message
    }
  } finally {
    state.loading = false
  }
}

async function onDiff() {
  if (!canDiff.value) return
  diff.loading = true
  diff.error = ''
  diff.text = ''
  try {
    const data = await diffRevisions(props.apiKind, props.name, pickB.value, pickA.value)
    diff.text = data?.unifiedDiff || ''
    diff.fromLabel = data?.fromVersionLabel || String(pickA.value)
    diff.toLabel = data?.toVersionLabel || String(pickB.value)
  } catch (e) {
    if (e instanceof VersionHistoryDisabledError) {
      state.disabled = true
      diff.error = ''
    } else {
      diff.error = e.message
    }
  } finally {
    diff.loading = false
  }
}

async function onRestore(row) {
  if (!row?.id || state.disabled) return
  if (!confirm(`将治理回滚到修订 #${row.id}（${row.versionLabel || '—'}）？会写回现网并追加新快照。`)) {
    return
  }
  restoreBusy.value = row.id
  state.error = ''
  try {
    await restoreRevision(props.apiKind, props.name, row.id, state.workspaceRevision)
    emit('restored')
    await load()
  } catch (e) {
    if (e instanceof VersionHistoryDisabledError) {
      state.disabled = true
    } else {
      state.error = e.message
    }
  } finally {
    restoreBusy.value = null
  }
}

watch(
  () => [props.apiKind, props.name],
  () => {
    load()
  },
  { immediate: true },
)

defineExpose({ load })
</script>

<template>
  <div class="gov-rev">
    <div class="sess-meta"><span>版本历史</span></div>
    <p class="lede">
      依赖 <span class="mono">oryxos.web.asset-governance.version-history-enabled</span>（默认关 →
      list 空；diff/restore 404）。选两版对比统一 diff，或回滚到某一快照。
    </p>

    <p v-if="state.disabled" class="empty muted">
      版本历史未启用：请在配置中打开
      <span class="mono">oryxos.web.asset-governance.version-history-enabled=true</span> 后刷新。
    </p>
    <p v-else-if="state.loading" class="empty">加载版本…</p>
    <p v-else-if="state.error" class="error">{{ state.error }}</p>
    <template v-else>
      <p v-if="!state.rows.length" class="empty muted">
        暂无版本快照。开启 version-history-enabled 并保存治理后会产生全文快照。
      </p>
      <template v-else>
        <div class="toolbar create-row">
          <select v-model="pickA" class="gen-input mono">
            <option value="">对比基线…</option>
            <option v-for="r in state.rows" :key="'a-' + r.id" :value="String(r.id)">
              {{ rowLabel(r) }}
            </option>
          </select>
          <select v-model="pickB" class="gen-input mono">
            <option value="">对比目标…</option>
            <option v-for="r in state.rows" :key="'b-' + r.id" :value="String(r.id)">
              {{ rowLabel(r) }}
            </option>
          </select>
          <button class="btn" :disabled="!canDiff" @click="onDiff">对比</button>
        </div>
        <p v-if="diff.error" class="error">{{ diff.error }}</p>
        <p v-else-if="diff.loading" class="empty">生成 diff…</p>
        <div v-else-if="diff.text" class="diff-box">
          <div class="sess-meta">
            <span class="mono">{{ diff.fromLabel }} → {{ diff.toLabel }}</span>
          </div>
          <pre class="mono diff-pre">{{ diff.text }}</pre>
        </div>

        <table>
          <thead>
            <tr>
              <th>id</th>
              <th>version</th>
              <th>actor</th>
              <th>createdAt</th>
              <th style="width:90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in state.rows" :key="r.id">
              <td class="mono">{{ r.id }}</td>
              <td class="mono">{{ r.versionLabel || '—' }}</td>
              <td>{{ r.actor || '—' }}</td>
              <td class="mono">{{ formatWhen(r.createdAt) }}</td>
              <td class="ops">
                <button
                  class="btn"
                  :disabled="restoreBusy === r.id"
                  @click="onRestore(r)"
                >
                  回滚
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </template>
    </template>
  </div>
</template>

<style scoped>
.gov-rev {
  margin-top: 14px;
}
.lede {
  margin: 0 0 10px;
  color: var(--text-2);
  line-height: 1.6;
  font-size: 13px;
}
.muted {
  opacity: 0.85;
}
.create-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
}
.create-row .gen-input {
  flex: 1 1 200px;
  min-width: 160px;
}
.diff-box {
  margin: 0 0 12px;
}
.diff-pre {
  margin: 6px 0 0;
  padding: 10px 12px;
  max-height: 280px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.45;
  background: var(--surface-2, rgba(0, 0, 0, 0.04));
  border-radius: 6px;
}
.ops {
  white-space: nowrap;
}
</style>
