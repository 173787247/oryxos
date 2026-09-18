import test from 'node:test'
import assert from 'node:assert/strict'
import {
  DEFAULT_MCP_REQUEST_TIMEOUT_SECONDS,
  normalizeMcpRequestTimeout,
} from './mcp-timeout.js'

test('MCP 请求超时接受边界内整数', () => {
  assert.equal(DEFAULT_MCP_REQUEST_TIMEOUT_SECONDS, 30)
  assert.equal(normalizeMcpRequestTimeout('240'), 240)
  assert.equal(normalizeMcpRequestTimeout(1), 1)
  assert.equal(normalizeMcpRequestTimeout(3600), 3600)
})

test('MCP 请求超时拒绝空值、非整数和越界值', () => {
  for (const invalid of ['', 0, -1, 1.5, 3601, 'not-a-number']) {
    assert.throws(() => normalizeMcpRequestTimeout(invalid), /1–3600/)
  }
})
