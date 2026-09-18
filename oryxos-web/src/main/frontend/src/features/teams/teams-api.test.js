import assert from 'node:assert/strict'
import test from 'node:test'

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

test('listTeams returns data on success', async () => {
  const restore = mockFetch(async (url) => {
    assert.equal(url, '/api/v1/teams')
    return jsonRes(200, { code: 0, data: [{ teamId: 'eng', displayName: 'Engineering' }] })
  })
  try {
    const data = await listTeams()
    assert.equal(data[0].teamId, 'eng')
  } finally {
    restore()
  }
})

test('listTeams throws TeamsApiDisabledError on 404', async () => {
  const restore = mockFetch(async () => jsonRes(404, { code: 404, message: 'teams api disabled' }))
  try {
    await assert.rejects(listTeams(), (err) => {
      assert.equal(err.name, 'TeamsApiDisabledError')
      assert.match(err.message, /teams api disabled/)
      return true
    })
  } finally {
    restore()
  }
})

test('createTeam posts teamId and displayName', async () => {
  let body
  const restore = mockFetch(async (url, opts) => {
    assert.equal(url, '/api/v1/teams')
    assert.equal(opts.method, 'POST')
    body = JSON.parse(opts.body)
    return jsonRes(200, { code: 0, data: { teamId: 'eng', displayName: 'Engineering' } })
  })
  try {
    const data = await createTeam('eng', 'Engineering')
    assert.deepEqual(body, { teamId: 'eng', displayName: 'Engineering' })
    assert.equal(data.teamId, 'eng')
  } finally {
    restore()
  }
})

test('renameTeam patches displayName', async () => {
  let body
  const restore = mockFetch(async (url, opts) => {
    assert.equal(url, '/api/v1/teams/eng')
    assert.equal(opts.method, 'PATCH')
    body = JSON.parse(opts.body)
    return jsonRes(200, { code: 0, data: { teamId: 'eng', displayName: 'Eng2' } })
  })
  try {
    await renameTeam('eng', 'Eng2')
    assert.deepEqual(body, { displayName: 'Eng2' })
  } finally {
    restore()
  }
})

test('deleteTeam sends DELETE', async () => {
  const restore = mockFetch(async (url, opts) => {
    assert.equal(url, '/api/v1/teams/eng')
    assert.equal(opts.method, 'DELETE')
    return jsonRes(200, { code: 0, data: null })
  })
  try {
    await deleteTeam('eng')
  } finally {
    restore()
  }
})

test('listUserTeams / add / remove encode username and teamId', async () => {
  const seen = []
  const restore = mockFetch(async (url, opts = {}) => {
    seen.push({ url, method: opts.method || 'GET' })
    if (String(url).includes('/users/')) {
      return jsonRes(200, { code: 0, data: { username: 'alice/bob', teamIds: ['eng'] } })
    }
    return jsonRes(200, { code: 0, data: [] })
  })
  try {
    await listUserTeams('alice/bob')
    await addUserTeam('alice/bob', 'a/b')
    await removeUserTeam('alice/bob', 'a/b')
    assert.deepEqual(seen, [
      { url: '/api/v1/users/alice%2Fbob/teams', method: 'GET' },
      { url: '/api/v1/users/alice%2Fbob/teams/a%2Fb', method: 'PUT' },
      { url: '/api/v1/users/alice%2Fbob/teams/a%2Fb', method: 'DELETE' },
    ])
  } finally {
    restore()
  }
})

test('non-zero code throws Error', async () => {
  const restore = mockFetch(async () => jsonRes(200, { code: 1, message: 'boom' }))
  try {
    await assert.rejects(listTeams(), /boom/)
  } finally {
    restore()
  }
})

test('TeamsApiDisabledError is Error subclass', () => {
  const err = new TeamsApiDisabledError()
  assert.ok(err instanceof Error)
  assert.equal(err.name, 'TeamsApiDisabledError')
})
