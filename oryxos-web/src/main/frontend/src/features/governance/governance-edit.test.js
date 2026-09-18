import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  blankGov,
  createGovernanceEdit,
  loadGovernance,
  saveGovernance,
  startEditGovernance,
} from './governance-edit.js'

describe('governance-edit helpers', () => {
  it('blankGov shows dash for empty', () => {
    assert.equal(blankGov(null), '—')
    assert.equal(blankGov(''), '—')
    assert.equal(blankGov('ACTIVE'), 'ACTIVE')
  })

  it('createGovernanceEdit starts closed unloaded', () => {
    const s = createGovernanceEdit()
    assert.equal(s.open, false)
    assert.equal(s.loaded, false)
    assert.equal(s.health, '')
    assert.equal(s.revision, null)
  })

  it('startEditGovernance opens editor', () => {
    const s = createGovernanceEdit()
    startEditGovernance(s)
    assert.equal(s.open, true)
  })

  it('round-trips the workspace revision on governance writes', async () => {
    const calls = []
    const originalFetch = globalThis.fetch
    globalThis.fetch = async (url, options = {}) => {
      calls.push({ url, options })
      const revision = calls.length === 1 ? 'read-revision' : 'write-revision'
      return {
        headers: { get: (name) => (name === 'X-Workspace-Revision' ? revision : null) },
        json: async () => ({ code: 0, data: { health: 'ACTIVE' } }),
      }
    }
    try {
      const state = createGovernanceEdit()
      await loadGovernance(state, 'agents', 'demo')
      await saveGovernance(state, 'agents', 'demo')

      assert.equal(calls[1].options.headers['If-Match'], 'read-revision')
      assert.equal(state.revision, 'write-revision')
    } finally {
      globalThis.fetch = originalFetch
    }
  })
})
