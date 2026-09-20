import assert from 'node:assert/strict'
import { test } from 'node:test'
import { ApprovalsApiDisabledError } from './approvals-api.js'

test('ApprovalsApiDisabledError name', () => {
  const e = new ApprovalsApiDisabledError()
  assert.equal(e.name, 'ApprovalsApiDisabledError')
  assert.match(e.message, /disabled/)
})
