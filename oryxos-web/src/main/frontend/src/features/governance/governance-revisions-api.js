import { revisionHeaders } from '../../workspace-revision.js'

/**
 * Governance revision history HTTP helpers (#550 / #537 / #541 / #544).
 * Flag oryxos.web.asset-governance.version-history-enabled default off →
 * list returns []; diff/restore → 404 (VersionHistoryDisabledError).
 *
 * apiKind: 'agents' | 'skills' | 'knowledge' | 'channels'
 */

export class VersionHistoryDisabledError extends Error {
  constructor(message = 'version history disabled') {
    super(message)
    this.name = 'VersionHistoryDisabledError'
  }
}

function basePath(apiKind, name) {
  return `/api/v1/${apiKind}/${encodeURIComponent(name)}/governance/revisions`
}

export async function listRevisions(apiKind, name, captureRevision) {
  const response = await fetch(basePath(apiKind, name))
  const data = await unwrap(response)
  captureRevision?.(response.headers.get('X-Workspace-Revision'))
  return data
}

export async function diffRevisions(apiKind, name, revisionId, againstId) {
  const url =
    `${basePath(apiKind, name)}/${encodeURIComponent(revisionId)}/diff` +
    `?against=${encodeURIComponent(againstId)}`
  return unwrap(await fetch(url))
}

export async function restoreRevision(apiKind, name, revisionId, workspaceRevision) {
  return unwrap(
    await fetch(`${basePath(apiKind, name)}/${encodeURIComponent(revisionId)}/restore`, {
      method: 'POST',
      headers: apiKind === 'channels' ? {} : revisionHeaders(workspaceRevision),
    }),
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
    const msg = body?.message || 'version history disabled'
    throw new VersionHistoryDisabledError(msg)
  }
  if (!body || body.code !== 0) {
    throw new Error(body?.message || `请求失败 (${res.status})`)
  }
  return body.data
}
