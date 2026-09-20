import assert from 'node:assert/strict'
import test from 'node:test'

import { buildOrgTreeRows } from './org-tree.js'

test('buildOrgTreeRows empty / non-array', () => {
  assert.deepEqual(buildOrgTreeRows([]), [])
  assert.deepEqual(buildOrgTreeRows(null), [])
})

test('buildOrgTreeRows roots first then children indented', () => {
  const rows = buildOrgTreeRows([
    { orgId: 'child', displayName: 'Child', parentOrgId: 'root' },
    { orgId: 'root', displayName: 'Root', parentOrgId: null },
    { orgId: 'sib', displayName: 'Sib', parentOrgId: null },
  ])
  assert.deepEqual(
    rows.map((r) => [r.orgId, r.depth, r.orphan]),
    [
      ['root', 0, false],
      ['child', 1, false],
      ['sib', 0, false],
    ],
  )
})

test('buildOrgTreeRows orphans become roots with orphan flag', () => {
  const rows = buildOrgTreeRows([
    { orgId: 'ghost-child', displayName: 'G', parentOrgId: 'missing' },
    { orgId: 'ok', displayName: 'Ok', parentOrgId: null },
  ])
  assert.deepEqual(
    rows.map((r) => [r.orgId, r.depth, r.orphan, r.parentOrgId]),
    [
      ['ok', 0, false, null],
      ['ghost-child', 0, true, 'missing'],
    ],
  )
})

test('buildOrgTreeRows multi-level and sibling order by orgId', () => {
  const rows = buildOrgTreeRows([
    { orgId: 'a', parentOrgId: null },
    { orgId: 'a2', parentOrgId: 'a' },
    { orgId: 'a1', parentOrgId: 'a' },
    { orgId: 'a1b', parentOrgId: 'a1' },
  ])
  assert.deepEqual(
    rows.map((r) => [r.orgId, r.depth]),
    [
      ['a', 0],
      ['a1', 1],
      ['a1b', 2],
      ['a2', 1],
    ],
  )
})

test('buildOrgTreeRows skips cycle edges without dropping nodes', () => {
  const rows = buildOrgTreeRows([
    { orgId: 'x', parentOrgId: 'y' },
    { orgId: 'y', parentOrgId: 'x' },
  ])
  assert.equal(rows.length, 2)
  assert.ok(rows.every((r) => r.depth === 0 || r.depth === 1))
  assert.deepEqual(new Set(rows.map((r) => r.orgId)), new Set(['x', 'y']))
})
