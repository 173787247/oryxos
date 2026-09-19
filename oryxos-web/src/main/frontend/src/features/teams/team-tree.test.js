import assert from 'node:assert/strict'
import test from 'node:test'

import { buildTeamTreeRows } from './team-tree.js'

test('buildTeamTreeRows empty / non-array', () => {
  assert.deepEqual(buildTeamTreeRows([]), [])
  assert.deepEqual(buildTeamTreeRows(null), [])
})

test('buildTeamTreeRows roots first then children indented', () => {
  const rows = buildTeamTreeRows([
    { teamId: 'child', displayName: 'Child', parentTeamId: 'root' },
    { teamId: 'root', displayName: 'Root', parentTeamId: null },
    { teamId: 'sib', displayName: 'Sib', parentTeamId: null },
  ])
  assert.deepEqual(
    rows.map((r) => [r.teamId, r.depth, r.orphan]),
    [
      ['root', 0, false],
      ['child', 1, false],
      ['sib', 0, false],
    ],
  )
})

test('buildTeamTreeRows orphans become roots with orphan flag', () => {
  const rows = buildTeamTreeRows([
    { teamId: 'ghost-child', displayName: 'G', parentTeamId: 'missing' },
    { teamId: 'ok', displayName: 'Ok', parentTeamId: null },
  ])
  assert.deepEqual(
    rows.map((r) => [r.teamId, r.depth, r.orphan, r.parentTeamId]),
    [
      ['ok', 0, false, null],
      ['ghost-child', 0, true, 'missing'],
    ],
  )
})

test('buildTeamTreeRows multi-level and sibling order by teamId', () => {
  const rows = buildTeamTreeRows([
    { teamId: 'a', parentTeamId: null },
    { teamId: 'a2', parentTeamId: 'a' },
    { teamId: 'a1', parentTeamId: 'a' },
    { teamId: 'a1b', parentTeamId: 'a1' },
  ])
  assert.deepEqual(
    rows.map((r) => [r.teamId, r.depth]),
    [
      ['a', 0],
      ['a1', 1],
      ['a1b', 2],
      ['a2', 1],
    ],
  )
})

test('buildTeamTreeRows skips cycle edges without dropping nodes', () => {
  const rows = buildTeamTreeRows([
    { teamId: 'x', parentTeamId: 'y' },
    { teamId: 'y', parentTeamId: 'x' },
  ])
  assert.equal(rows.length, 2)
  assert.ok(rows.every((r) => r.depth === 0 || r.depth === 1))
  assert.deepEqual(new Set(rows.map((r) => r.teamId)), new Set(['x', 'y']))
})

test('buildTeamTreeRows preserves orgId on rows', () => {
  const rows = buildTeamTreeRows([
    { teamId: 't1', orgId: 'o1', parentTeamId: null },
  ])
  assert.equal(rows[0].orgId, 'o1')
})
