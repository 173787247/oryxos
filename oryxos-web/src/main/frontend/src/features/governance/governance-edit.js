/**
 * Shared GOVERNANCE.yml editor state + API helpers (#504).
 * apiKind: 'agents' | 'skills' | 'knowledge'
 */
export function blankGov(v) {
  return v == null || v === '' ? '—' : v
}

export function createGovernanceEdit() {
  return {
    open: false,
    loading: false,
    saving: false,
    error: '',
    owner: '',
    version: '',
    visibility: '',
    riskLevel: '',
    health: '',
    teamOwner: '',
    loaded: false,
  }
}

function applyData(state, g) {
  const data = g || {}
  state.owner = data.owner || ''
  state.version = data.version || ''
  state.visibility = data.visibility || ''
  state.riskLevel = data.riskLevel || ''
  state.health = data.health || ''
  state.teamOwner = data.teamOwner || ''
  state.loaded = true
}

export async function loadGovernance(state, apiKind, name) {
  state.loading = true
  state.error = ''
  state.open = false
  state.loaded = false
  try {
    const res = await fetch(`/api/v1/${apiKind}/${encodeURIComponent(name)}/governance`)
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '治理加载失败')
    applyData(state, body.data)
  } catch (e) {
    state.error = e.message
  } finally {
    state.loading = false
  }
}

export function startEditGovernance(state) {
  state.open = true
  state.error = ''
  state.saving = false
}

export async function cancelEditGovernance(state, apiKind, name) {
  state.open = false
  if (name) await loadGovernance(state, apiKind, name)
}

export async function saveGovernance(state, apiKind, name) {
  state.saving = true
  state.error = ''
  try {
    const res = await fetch(`/api/v1/${apiKind}/${encodeURIComponent(name)}/governance`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        owner: state.owner.trim() || null,
        version: state.version.trim() || null,
        visibility: state.visibility.trim() || null,
        riskLevel: state.riskLevel.trim() || null,
        health: state.health.trim() || null,
        teamOwner: state.teamOwner.trim() || null,
      }),
    })
    const body = await res.json()
    if (body.code !== 0) throw new Error(body.message || '保存失败')
    applyData(state, body.data)
    state.open = false
  } catch (e) {
    state.error = e.message
  } finally {
    state.saving = false
  }
}
