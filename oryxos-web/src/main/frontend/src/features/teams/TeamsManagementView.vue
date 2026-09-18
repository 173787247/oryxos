<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  TeamsApiDisabledError,
  addUserTeam,
  createTeam,
  deleteTeam,
  listTeams,
  listUserTeams,
  removeUserTeam,
  renameTeam,
} from './teams-api.js'

const catalog = ref({ loading: true, error: null, disabled: false, data: [] })
const createForm = reactive({ teamId: '', displayName: '', busy: false, error: '' })
const renameForm = reactive({ teamId: '', displayName: '', busy: false, error: '' })
const member = reactive({
  username: '',
  loadedFor: '',
  teamIds: [],
  loading: false,
  error: '',
  disabled: false,
  addTeamId: '',
  busy: false,
})

const canCreate = computed(() => createForm.teamId.trim().length > 0 && !createForm.busy)
const canRename = computed(() => renameForm.displayName.trim().length > 0 && !renameForm.busy)
const canLoadMembers = computed(() => member.username.trim().length > 0 && !member.loading)
const canAddMember = computed(
  () => member.loadedFor && member.addTeamId.trim().length > 0 && !member.busy,
)

function applyCatalogError(e) {
  if (e instanceof TeamsApiDisabledError) {
    catalog.value = { loading: false, error: null, disabled: true, data: [] }
    return
  }
  catalog.value = { loading: false, error: e.message, disabled: false, data: [] }
}

async function load() {
  catalog.value = { loading: true, error: null, disabled: false, data: catalog.value.data || [] }
  try {
    const data = await listTeams()
    catalog.value = { loading: false, error: null, disabled: false, data: data || [] }
  } catch (e) {
    applyCatalogError(e)
  }
}

async function onCreate() {
  if (!canCreate.value) return
  createForm.busy = true
  createForm.error = ''
  try {
    await createTeam(createForm.teamId.trim(), createForm.displayName.trim())
    createForm.teamId = ''
    createForm.displayName = ''
    await load()
  } catch (e) {
    if (e instanceof TeamsApiDisabledError) {
      catalog.value = { loading: false, error: null, disabled: true, data: [] }
    } else {
      createForm.error = e.message
    }
  } finally {
    createForm.busy = false
  }
}

function startRename(row) {
  renameForm.teamId = row.teamId
  renameForm.displayName = row.displayName || ''
  renameForm.error = ''
}

function cancelRename() {
  renameForm.teamId = ''
  renameForm.displayName = ''
  renameForm.error = ''
  renameForm.busy = false
}

async function onRename() {
  if (!canRename.value || !renameForm.teamId) return
  renameForm.busy = true
  renameForm.error = ''
  try {
    await renameTeam(renameForm.teamId, renameForm.displayName.trim())
    cancelRename()
    await load()
  } catch (e) {
    renameForm.error = e.message
  } finally {
    renameForm.busy = false
  }
}

async function onDelete(row) {
  if (!row?.teamId) return
  if (!confirm(`删除团队目录「${row.teamId}」？成员关系不会随本页自动清理。`)) return
  try {
    await deleteTeam(row.teamId)
    if (renameForm.teamId === row.teamId) cancelRename()
    await load()
  } catch (e) {
    catalog.value = {
      ...catalog.value,
      error: e.message,
      disabled: e instanceof TeamsApiDisabledError,
    }
  }
}

async function onLoadMembers() {
  const username = member.username.trim()
  if (!username) return
  member.loading = true
  member.error = ''
  member.disabled = false
  try {
    const data = await listUserTeams(username)
    member.loadedFor = data?.username || username
    member.teamIds = data?.teamIds || []
  } catch (e) {
    member.loadedFor = ''
    member.teamIds = []
    if (e instanceof TeamsApiDisabledError) {
      member.disabled = true
      member.error = ''
    } else {
      member.error = e.message
    }
  } finally {
    member.loading = false
  }
}

async function onAddMember() {
  if (!canAddMember.value) return
  member.busy = true
  member.error = ''
  try {
    const data = await addUserTeam(member.loadedFor, member.addTeamId.trim())
    member.teamIds = data?.teamIds || []
    member.addTeamId = ''
  } catch (e) {
    member.error = e.message
  } finally {
    member.busy = false
  }
}

async function onRemoveMember(teamId) {
  if (!member.loadedFor || !teamId) return
  member.busy = true
  member.error = ''
  try {
    const data = await removeUserTeam(member.loadedFor, teamId)
    member.teamIds = data?.teamIds || []
  } catch (e) {
    member.error = e.message
  } finally {
    member.busy = false
  }
}

onMounted(() => {
  load()
})

defineExpose({ load })
</script>

<template>
  <div class="teams">
    <p class="lede">
      管理团队目录与用户成员关系。依赖 <span class="mono">oryxos.web.teams-api.enabled</span>（默认关 →
      API 404）。需 ADMIN / <span class="mono">MANAGE_MEMBERS</span>。无 orgs / JIT。
    </p>

    <p v-if="catalog.disabled" class="error">
      团队 API 未启用：请在配置中打开 <span class="mono">oryxos.web.teams-api.enabled=true</span> 后刷新。
    </p>
    <p v-else-if="catalog.loading" class="empty">加载中…</p>
    <p v-else-if="catalog.error" class="error">出错：{{ catalog.error }}</p>

    <template v-if="!catalog.disabled">
      <h3 class="sec">团队目录</h3>
      <div class="toolbar create-row">
        <input v-model="createForm.teamId" class="gen-input mono" placeholder="teamId（必填）" />
        <input v-model="createForm.displayName" class="gen-input" placeholder="displayName（可选）" />
        <button class="btn btn-primary" :disabled="!canCreate" @click="onCreate">创建</button>
      </div>
      <p v-if="createForm.error" class="error">{{ createForm.error }}</p>

      <div v-if="renameForm.teamId" class="toolbar create-row">
        <span class="mono">重命名 {{ renameForm.teamId }}</span>
        <input v-model="renameForm.displayName" class="gen-input" placeholder="新 displayName" />
        <button class="btn btn-primary" :disabled="!canRename" @click="onRename">保存</button>
        <button class="btn" :disabled="renameForm.busy" @click="cancelRename">取消</button>
      </div>
      <p v-if="renameForm.error" class="error">{{ renameForm.error }}</p>

      <table v-if="!catalog.loading">
        <thead>
          <tr>
            <th>teamId</th>
            <th>displayName</th>
            <th style="width:160px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!catalog.data.length">
            <td colspan="3" class="empty">（暂无团队目录）</td>
          </tr>
          <tr v-for="t in catalog.data" :key="t.teamId">
            <td class="mono">{{ t.teamId }}</td>
            <td>{{ t.displayName || '—' }}</td>
            <td class="ops">
              <button class="btn" @click="startRename(t)">重命名</button>
              <button class="btn" @click="onDelete(t)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>

      <h3 class="sec" style="margin-top:24px">用户成员</h3>
      <p class="empty">按用户查看/增删团队成员（API 为 user→teams；无按团队列成员端点）。</p>
      <div class="toolbar create-row">
        <input
          v-model="member.username"
          class="gen-input mono"
          placeholder="username"
          @keyup.enter="onLoadMembers"
        />
        <button class="btn" :disabled="!canLoadMembers" @click="onLoadMembers">加载成员</button>
      </div>
      <p v-if="member.disabled" class="error">团队 API 未启用。</p>
      <p v-else-if="member.error" class="error">{{ member.error }}</p>
      <p v-else-if="member.loading" class="empty">加载成员…</p>
      <template v-else-if="member.loadedFor">
        <div class="sess-meta">
          <span>用户</span>
          <span class="mono">{{ member.loadedFor }}</span>
        </div>
        <div class="toolbar create-row">
          <input v-model="member.addTeamId" class="gen-input mono" placeholder="要加入的 teamId" />
          <button class="btn btn-primary" :disabled="!canAddMember" @click="onAddMember">加入团队</button>
        </div>
        <table>
          <thead>
            <tr>
              <th>teamId</th>
              <th style="width:90px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!member.teamIds.length">
              <td colspan="2" class="empty">（该用户暂无团队成员）</td>
            </tr>
            <tr v-for="id in member.teamIds" :key="id">
              <td class="mono">{{ id }}</td>
              <td class="ops">
                <button class="btn" :disabled="member.busy" @click="onRemoveMember(id)">移除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </template>
    </template>
  </div>
</template>

<style scoped>
.teams { max-width: 920px; }
.lede {
  margin: 0 0 14px;
  color: var(--text-2);
  line-height: 1.6;
  font-size: 13px;
}
.sec {
  margin: 8px 0 10px;
  font-size: 14px;
  font-weight: 600;
}
.create-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
}
.create-row .gen-input {
  flex: 1 1 160px;
  min-width: 140px;
}
.ops { white-space: nowrap; }
</style>
