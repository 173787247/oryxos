import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { blankGov, createGovernanceEdit, startEditGovernance } from './governance-edit.js'

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
  })

  it('startEditGovernance opens editor', () => {
    const s = createGovernanceEdit()
    startEditGovernance(s)
    assert.equal(s.open, true)
  })
})
