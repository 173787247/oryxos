import { test } from 'node:test'
import assert from 'node:assert/strict'
import { withRevision, rowsWithRevision, revisionHeaders } from './workspace-revision.js'
const response = (revision) => ({ headers: new Headers({ 'X-Workspace-Revision': revision }) })

test('loaded form keeps its own revision when a different snapshot is fetched', () => {
  const old = withRevision({ body: 'old' }, response('R0'))
  const draft = { body: old.body, revision: old._workspaceRevision }
  const fresh = withRevision({ body: 'new' }, response('R1'))
  assert.deepEqual(revisionHeaders(draft.revision), { 'If-Match': 'R0' })
  assert.equal(fresh._workspaceRevision, 'R1')
  assert.equal(draft.body, 'old')
})
test('list rows carry delete/edit revision and missing token fails closed', () => {
  const rows = rowsWithRevision([{ name: 'one' }, { name: 'two' }], response('R0'))
  assert.deepEqual(revisionHeaders(rows[1]._workspaceRevision), { 'If-Match': 'R0' })
  assert.throws(() => revisionHeaders(null), /重新加载/)
})
