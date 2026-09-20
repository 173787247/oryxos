import assert from 'node:assert/strict'
import test from 'node:test'

import {
  VersionHistoryDisabledError,
  diffRevisions,
  listRevisions,
  restoreRevision,
} from './governance-revisions-api.js'

function mockFetch(handler) {
  const previous = globalThis.fetch
  globalThis.fetch = handler
  return () => {
    globalThis.fetch = previous
  }
}

function jsonRes(status, body) {
  return {
    status,
    json: async () => body,
  }
}

test('listRevisions returns data on success', async () => {
  const restore = mockFetch(async (url) => {
    assert.equal(url, '/api/v1/agents/bot/governance/revisions')
    return jsonRes(200, {
      code: 0,
      data: [{ id: 1, versionLabel: 'v1', actor: 'alice', createdAt: '2026-01-01T00:00:00Z' }],
    })
  })
  try {
    const data = await listRevisions('agents', 'bot')
    assert.equal(data[0].id, 1)
  } finally {
    restore()
  }
})

test('listRevisions encodes name', async () => {
  const restore = mockFetch(async (url) => {
    assert.equal(url, '/api/v1/skills/a%2Fb/governance/revisions')
    return jsonRes(200, { code: 0, data: [] })
  })
  try {
    await listRevisions('skills', 'a/b')
  } finally {
    restore()
  }
})

test('diffRevisions builds against query', async () => {
  const restore = mockFetch(async (url) => {
    assert.equal(url, '/api/v1/knowledge/kb/governance/revisions/3/diff?against=1')
    return jsonRes(200, {
      code: 0,
      data: { fromId: 1, toId: 3, unifiedDiff: '--- a\n+++ b\n' },
    })
  })
  try {
    const data = await diffRevisions('knowledge', 'kb', 3, 1)
    assert.match(data.unifiedDiff, /\+\+\+/)
  } finally {
    restore()
  }
})

test('restoreRevision posts and returns data', async () => {
  const restore = mockFetch(async (url, opts) => {
    assert.equal(url, '/api/v1/channels/web/governance/revisions/2/restore')
    assert.equal(opts.method, 'POST')
    return jsonRes(200, { code: 0, data: { health: 'ACTIVE' } })
  })
  try {
    const data = await restoreRevision('channels', 'web', 2)
    assert.equal(data.health, 'ACTIVE')
  } finally {
    restore()
  }
})

test('404 throws VersionHistoryDisabledError', async () => {
  const restore = mockFetch(async () =>
    jsonRes(404, { code: 404, message: 'version history disabled' }),
  )
  try {
    await assert.rejects(listRevisions('agents', 'bot'), (err) => {
      assert.equal(err.name, 'VersionHistoryDisabledError')
      assert.match(err.message, /version history disabled/)
      return true
    })
  } finally {
    restore()
  }
})

test('non-zero code throws Error', async () => {
  const restore = mockFetch(async () => jsonRes(200, { code: 1, message: 'boom' }))
  try {
    await assert.rejects(listRevisions('agents', 'bot'), /boom/)
  } finally {
    restore()
  }
})

test('VersionHistoryDisabledError is Error subclass', () => {
  const err = new VersionHistoryDisabledError()
  assert.ok(err instanceof Error)
  assert.equal(err.name, 'VersionHistoryDisabledError')
})

test('workspace restore rejects missing revision before fetch', async () => {
  const restore = mockFetch(async () => { throw new Error('unexpected fetch') })
  try {
    await assert.rejects(restoreRevision('agents', 'bot', 2), /版本/)
  } finally { restore() }
})

test('workspace restore uses revision captured with the list', async () => {
  const restore = mockFetch(async (url, opts) => {
    if (!opts) {
      return new Response(JSON.stringify({ code: 0, data: [] }), {
        headers: { 'X-Workspace-Revision': 'snapshot-1' },
      })
    }
    assert.equal(opts.headers['If-Match'], 'snapshot-1')
    return jsonRes(200, { code: 0, data: {} })
  })
  try {
    let revision
    await listRevisions('agents', 'bot', value => { revision = value })
    await restoreRevision('agents', 'bot', 2, revision)
  } finally { restore() }
})
