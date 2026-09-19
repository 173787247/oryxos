<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  IdentityMappingsApiDisabledError,
  deleteIdentityMapping,
  listIdentityMappings,
  upsertIdentityMapping,
} from './identity-mappings-api.js'

const state = ref({ loading: true, error: null, disabled: false, data: [] })
const form = reactive({
  issuer: '',
  subject: '',
  username: '',
  email: '',
  busy: false,
  error: '',
})

const canCreate = computed(
  () =>
    form.issuer.trim().length > 0 &&
    form.subject.trim().length > 0 &&
    form.username.trim().length > 0 &&
    !form.busy,
)

function applyDisabled(e) {
  if (e instanceof IdentityMappingsApiDisabledError) {
    state.value = { loading: false, error: null, disabled: true, data: [] }
    return true
  }
  return false
}

async function load() {
  state.value = { loading: true, error: null, disabled: false, data: state.value.data || [] }
  try {
    const data = await listIdentityMappings()
    state.value = { loading: false, error: null, disabled: false, data: data || [] }
  } catch (e) {
    if (applyDisabled(e)) return
    state.value = { loading: false, error: e.message, disabled: false, data: [] }
  }
}

async function onCreate() {
  if (!canCreate.value) return
  form.busy = true
  form.error = ''
  try {
    await upsertIdentityMapping(
      form.issuer.trim(),
      form.subject.trim(),
      form.username.trim(),
      form.email.trim(),
    )
    form.issuer = ''
    form.subject = ''
    form.username = ''
    form.email = ''
    await load()
  } catch (e) {
    if (applyDisabled(e)) return
    form.error = e.message
  } finally {
    form.busy = false
  }
}

async function onDelete(row) {
  if (!row?.issuer || !row?.subject) return
  if (!confirm(`删除映射 issuer=${row.issuer} subject=${row.subject}？`)) return
  try {
    await deleteIdentityMapping(row.issuer, row.subject)
    await load()
  } catch (e) {
    if (applyDisabled(e)) return
    state.value = { ...state.value, error: e.message }
  }
}

onMounted(() => {
  load()
})

defineExpose({ load })
</script>

<template>
  <div class="identity-mappings">
    <p class="lede">
      管理 OIDC <span class="mono">identity_mappings</span>（issuer + subject → 本地 username）。依赖
      <span class="mono">oryxos.web.oidc.mappings-api-enabled</span>（默认关 → API 404）。需 ADMIN /
      <span class="mono">MANAGE_MEMBERS</span>。无多 IdP discovery / OIDC→org JIT。
    </p>

    <p v-if="state.disabled" class="error">
      identity_mappings API 未启用：请在配置中打开
      <span class="mono">oryxos.web.oidc.mappings-api-enabled=true</span> 后刷新。
    </p>
    <p v-else-if="state.loading" class="empty">加载中…</p>
    <p v-else-if="state.error" class="error">出错：{{ state.error }}</p>

    <template v-if="!state.disabled">
      <h3 class="sec">新建 / 更新映射</h3>
      <div class="toolbar create-row">
        <input v-model="form.issuer" class="gen-input mono" placeholder="issuer（必填）" />
        <input v-model="form.subject" class="gen-input mono" placeholder="subject（必填）" />
        <input v-model="form.username" class="gen-input mono" placeholder="username（必填）" />
        <input v-model="form.email" class="gen-input" placeholder="email（可选）" />
        <button class="btn btn-primary" :disabled="!canCreate" @click="onCreate">保存</button>
      </div>
      <p v-if="form.error" class="error">{{ form.error }}</p>

      <h3 class="sec" style="margin-top:24px">映射列表</h3>
      <table v-if="!state.loading">
        <thead>
          <tr>
            <th>issuer</th>
            <th>subject</th>
            <th>username</th>
            <th>email</th>
            <th style="width:100px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!state.data.length">
            <td colspan="5" class="empty">（暂无映射）</td>
          </tr>
          <tr v-for="row in state.data" :key="row.issuer + '\0' + row.subject">
            <td class="mono">{{ row.issuer }}</td>
            <td class="mono">{{ row.subject }}</td>
            <td class="mono">{{ row.username }}</td>
            <td>{{ row.email || '—' }}</td>
            <td class="ops">
              <button class="btn" @click="onDelete(row)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </template>
  </div>
</template>
