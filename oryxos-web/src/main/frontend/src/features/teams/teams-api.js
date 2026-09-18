/**
 * Teams Admin HTTP helpers (#548): catalog + user memberships via /api/v1/teams.
 * Flag oryxos.web.teams-api.enabled default off → 404 (TeamsApiDisabledError).
 */

export class TeamsApiDisabledError extends Error {
  constructor(message = 'teams api disabled') {
    super(message)
    this.name = 'TeamsApiDisabledError'
  }
}

export async function listTeams() {
  return unwrap(await fetch('/api/v1/teams'))
}

export async function createTeam(teamId, displayName) {
  return unwrap(
    await fetch('/api/v1/teams', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ teamId, displayName: displayName || null }),
    }),
  )
}

export async function renameTeam(teamId, displayName) {
  return unwrap(
    await fetch(`/api/v1/teams/${encodeURIComponent(teamId)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ displayName }),
    }),
  )
}

export async function deleteTeam(teamId) {
  return unwrap(
    await fetch(`/api/v1/teams/${encodeURIComponent(teamId)}`, { method: 'DELETE' }),
  )
}

export async function listUserTeams(username) {
  return unwrap(await fetch(`/api/v1/users/${encodeURIComponent(username)}/teams`))
}

export async function addUserTeam(username, teamId) {
  return unwrap(
    await fetch(
      `/api/v1/users/${encodeURIComponent(username)}/teams/${encodeURIComponent(teamId)}`,
      { method: 'PUT' },
    ),
  )
}

export async function removeUserTeam(username, teamId) {
  return unwrap(
    await fetch(
      `/api/v1/users/${encodeURIComponent(username)}/teams/${encodeURIComponent(teamId)}`,
      { method: 'DELETE' },
    ),
  )
}

async function unwrap(res) {
  let body
  try {
    body = await res.json()
  } catch {
    body = null
  }
  if (res.status === 404) {
    const msg = body?.message || 'teams api disabled'
    throw new TeamsApiDisabledError(msg)
  }
  if (!body || body.code !== 0) {
    throw new Error(body?.message || `请求失败 (${res.status})`)
  }
  return body.data
}
