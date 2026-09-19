import assert from 'node:assert/strict'
import test from 'node:test'

import {
  IdentityMappingsApiDisabledError,
  deleteIdentityMapping,
  listIdentityMappings,
  upsertIdentityMapping,
} from './identity-mappings-api.js'

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

test('listIdentityMappings returns data on success', async () => {
  const restore = mockFetch(async (url) => {
    assert.equal(url, '/api/v1/identity-mappings')
    return jsonRes(200, {
      code: 0,
      data: [{ issuer: 'https://idp', subject: 's1', username: 'alice', email: null }],
    })
  })
  try {
    const data = await listIdentityMappings()
    assert.equal(data[0].username, 'alice')
  } finally {
    restore()
  }
})

test('listIdentityMappings throws IdentityMappingsApiDisabledError on 404', async () => {
  const restore = mockFetch(async () =>
    jsonRes(404, { code: 404, message: 'identity mappings api disabled' }),
  )
  try {
    await assert.rejects(listIdentityMappings(), (err) => {
      assert.equal(err.name, 'IdentityMappingsApiDisabledError')
      assert.match(err.message, /identity mappings api disabled/)
      return true
    })
  } finally {
    restore()
  }
})

test('upsertIdentityMapping posts fields', async () => {
  let body
  const restore = mockFetch(async (url, opts) => {
    assert.equal(url, '/api/v1/identity-mappings')
    assert.equal(opts.method, 'POST')
    body = JSON.parse(opts.body)
    return jsonRes(200, {
      code: 0,
      data: { issuer: 'https://idp', subject: 's1', username: 'alice', email: null },
    })
  })
  try {
    const data = await upsertIdentityMapping('https://idp', 's1', 'alice', '')
    assert.deepEqual(body, {
      issuer: 'https://idp',
      subject: 's1',
      username: 'alice',
      email: null,
    })
    assert.equal(data.username, 'alice')
  } finally {
    restore()
  }
})

test('deleteIdentityMapping uses query params', async () => {
  const restore = mockFetch(async (url, opts) => {
    assert.equal(opts.method, 'DELETE')
    assert.match(url, /^\/api\/v1\/identity-mappings\?/)
    const qs = new URL(url, 'http://local').searchParams
    assert.equal(qs.get('issuer'), 'https://idp')
    assert.equal(qs.get('subject'), 's1')
    return jsonRes(200, { code: 0, data: null })
  })
  try {
    await deleteIdentityMapping('https://idp', 's1')
  } finally {
    restore()
  }
})
