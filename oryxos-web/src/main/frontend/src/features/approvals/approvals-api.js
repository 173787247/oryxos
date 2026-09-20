/**
 * Admin approvals API（044 / #466）。
 * flag `oryxos.approval.interaction-api-enabled` 关 → 404。
 */

export class ApprovalsApiDisabledError extends Error {
  constructor() {
    super('approval interaction api disabled')
    this.name = 'ApprovalsApiDisabledError'
  }
}

async function parse(res) {
  if (res.status === 404) {
    throw new ApprovalsApiDisabledError()
  }
  const body = await res.json().catch(() => ({}))
  if (!res.ok || body.code !== 0) {
    throw new Error(body.message || `HTTP ${res.status}`)
  }
  return body.data
}

export async function listApprovals() {
  return parse(await fetch('/api/v1/approvals'))
}

export async function getApproval(checkpointId) {
  return parse(await fetch(`/api/v1/approvals/${encodeURIComponent(checkpointId)}`))
}

export async function decideApproval(checkpointId, { approved, actor, comment, argumentsJson }) {
  return parse(
    await fetch(`/api/v1/approvals/${encodeURIComponent(checkpointId)}/decide`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ approved, actor, comment, argumentsJson }),
    }),
  )
}
