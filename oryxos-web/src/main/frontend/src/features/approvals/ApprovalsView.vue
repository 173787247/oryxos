<script setup>
import { onMounted, reactive, ref } from 'vue'
import {
  ApprovalsApiDisabledError,
  decideApproval,
  listApprovals,
} from './approvals-api.js'

const state = ref({ loading: true, error: null, disabled: false, data: [] })
const draft = reactive({})

function applyDisabled(e) {
  if (e instanceof ApprovalsApiDisabledError) {
    state.value = { loading: false, error: null, disabled: true, data: [] }
    return true
  }
  return false
}

async function load() {
  state.value = { loading: true, error: null, disabled: false, data: state.value.data || [] }
  try {
    const data = await listApprovals()
    state.value = { loading: false, error: null, disabled: false, data: data || [] }
    for (const row of data || []) {
      if (!draft[row.checkpointId]) {
        draft[row.checkpointId] = {
          comment: '',
          argumentsJson: row.argumentsJson || '',
          busy: false,
          error: '',
        }
      }
    }
  } catch (e) {
    if (applyDisabled(e)) return
    state.value = { loading: false, error: e.message, disabled: false, data: [] }
  }
}

async function decide(row, approved) {
  const d = draft[row.checkpointId] || { comment: '', argumentsJson: row.argumentsJson, busy: false }
  d.busy = true
  d.error = ''
  try {
    await decideApproval(row.checkpointId, {
      approved,
      actor: 'admin',
      comment: d.comment || undefined,
      argumentsJson: approved ? d.argumentsJson || undefined : undefined,
    })
    await load()
  } catch (e) {
    if (applyDisabled(e)) return
    d.error = e.message
  } finally {
    d.busy = false
  }
}

onMounted(() => {
  load()
})

defineExpose({ load })
</script>

<template>
  <div class="approvals">
    <p class="lede">
      HITL 待审批任务（#466）。依赖
      <span class="mono">oryxos.approval.interaction-api-enabled</span>
      与耐久挂起（<span class="mono">durable-suspend</span>）。批准可改参后经
      <span class="mono">DurableTaskReplay</span> 回放；飞书/企微走统一回调 stub。
    </p>

    <p v-if="state.disabled" class="error">
      审批交互 API 未启用：请打开
      <span class="mono">oryxos.approval.interaction-api-enabled=true</span> 后刷新。
    </p>
    <p v-else-if="state.loading" class="empty">加载中…</p>
    <p v-else-if="state.error" class="error">出错：{{ state.error }}</p>

    <template v-if="!state.disabled && !state.loading">
      <div class="toolbar" style="margin-bottom: 12px">
        <button class="btn" @click="load">刷新</button>
      </div>
      <table>
        <thead>
          <tr>
            <th>checkpoint</th>
            <th>agent / tool</th>
            <th>时效</th>
            <th>参数 / 意见</th>
            <th style="width: 160px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!state.data.length">
            <td colspan="5" class="empty">（无 WAITING_APPROVAL）</td>
          </tr>
          <tr v-for="row in state.data" :key="row.checkpointId">
            <td class="mono">{{ row.checkpointId }}</td>
            <td>
              <div class="mono">{{ row.agentName }}</div>
              <div class="mono">{{ row.toolName }}</div>
            </td>
            <td>
              <span v-if="row.expired" class="error">已过期</span>
              <span v-else class="mono">{{ row.expiresAt || '—' }}</span>
              <div class="mono" style="opacity: 0.7">ttl={{ row.ttlSeconds ?? '—' }}s</div>
            </td>
            <td>
              <textarea
                v-model="draft[row.checkpointId].argumentsJson"
                class="gen-input mono"
                rows="3"
                style="width: 100%"
              />
              <input
                v-model="draft[row.checkpointId].comment"
                class="gen-input"
                placeholder="意见（可选）"
                style="width: 100%; margin-top: 6px"
              />
              <p v-if="draft[row.checkpointId]?.error" class="error">
                {{ draft[row.checkpointId].error }}
              </p>
            </td>
            <td class="ops">
              <button
                class="btn btn-primary"
                :disabled="draft[row.checkpointId]?.busy || row.expired"
                @click="decide(row, true)"
              >
                批准
              </button>
              <button
                class="btn"
                :disabled="draft[row.checkpointId]?.busy"
                @click="decide(row, false)"
              >
                拒绝
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </template>
  </div>
</template>
