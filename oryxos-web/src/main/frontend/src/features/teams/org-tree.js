/**
 * Flatten OrganizationView list into depth-first tree rows for Admin UI.
 * Roots: missing/empty parentOrgId. Orphans (parent not in list) are roots with orphan=true.
 * Cycles: skip already-visiting edges; leftover nodes appended as roots.
 */
export function buildOrgTreeRows(orgs) {
  const list = Array.isArray(orgs) ? orgs.filter((o) => o && o.orgId) : []
  const byId = new Map(list.map((o) => [o.orgId, o]))
  const children = new Map()
  const roots = []
  const orphans = []

  for (const o of list) {
    const parent = o.parentOrgId
    if (!parent) {
      roots.push(o)
    } else if (!byId.has(parent)) {
      orphans.push(o)
    } else {
      if (!children.has(parent)) children.set(parent, [])
      children.get(parent).push(o)
    }
  }

  const byOrgId = (a, b) => String(a.orgId).localeCompare(String(b.orgId))
  roots.sort(byOrgId)
  orphans.sort(byOrgId)
  for (const kids of children.values()) kids.sort(byOrgId)

  const rows = []
  const visiting = new Set()
  const visited = new Set()

  function walk(org, depth, orphan) {
    if (!org?.orgId || visited.has(org.orgId)) return
    if (visiting.has(org.orgId)) return
    visiting.add(org.orgId)
    rows.push({
      orgId: org.orgId,
      displayName: org.displayName ?? null,
      parentOrgId: org.parentOrgId ?? null,
      depth,
      orphan: !!orphan,
    })
    visited.add(org.orgId)
    for (const child of children.get(org.orgId) || []) {
      walk(child, depth + 1, false)
    }
    visiting.delete(org.orgId)
  }

  for (const r of roots) walk(r, 0, false)
  for (const o of orphans) walk(o, 0, true)
  for (const o of list) {
    if (!visited.has(o.orgId)) walk(o, 0, false)
  }
  return rows
}
