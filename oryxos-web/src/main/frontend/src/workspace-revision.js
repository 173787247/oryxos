// Keep the revision with the representation that was actually loaded, never a global latest token.
export function withRevision(value, response) {
  return { ...value, _workspaceRevision: response.headers.get('X-Workspace-Revision') }
}

export function rowsWithRevision(values, response) {
  return (values || []).map((value) => withRevision(value, response))
}

export function revisionHeaders(revision) {
  if (!revision) throw new Error('请重新加载内容后再保存，当前编辑快照缺少版本。')
  return { 'If-Match': revision }
}
