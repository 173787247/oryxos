/**
 * OIDC identity_mappings Admin HTTP helpers (#577): list / upsert / delete via
 * /api/v1/identity-mappings.
 * Flag oryxos.web.oidc.mappings-api-enabled default off → 404 (IdentityMappingsApiDisabledError).
 */

export class IdentityMappingsApiDisabledError extends Error {
  constructor(message = 'identity mappings api disabled') {
    super(message)
    this.name = 'IdentityMappingsApiDisabledError'
  }
}

export async function listIdentityMappings() {
  return unwrap(await fetch('/api/v1/identity-mappings'))
}

export async function upsertIdentityMapping(issuer, subject, username, email) {
  const body = {
    issuer,
    subject,
    username,
    email: email == null || String(email).trim() === '' ? null : String(email).trim(),
  }
  return unwrap(
    await fetch('/api/v1/identity-mappings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
  )
}

export async function deleteIdentityMapping(issuer, subject) {
  const qs = new URLSearchParams({ issuer, subject })
  return unwrap(await fetch(`/api/v1/identity-mappings?${qs.toString()}`, { method: 'DELETE' }))
}

async function unwrap(res) {
  let body
  try {
    body = await res.json()
  } catch {
    body = null
  }
  if (res.status === 404) {
    const msg = body?.message || 'identity mappings api disabled'
    throw new IdentityMappingsApiDisabledError(msg)
  }
  if (!body || body.code !== 0) {
    throw new Error(body?.message || `请求失败 (${res.status})`)
  }
  return body.data
}
