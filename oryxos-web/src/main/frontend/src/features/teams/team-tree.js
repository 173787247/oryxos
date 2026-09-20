/**
 * Flatten TeamView list into depth-first tree rows for Admin UI.
 * Roots: missing/empty parentTeamId. Orphans (parent not in list) are roots with orphan=true.
 * Cycles: skip already-visiting edges; leftover nodes appended as roots.
 */
export function buildTeamTreeRows(teams) {
  const list = Array.isArray(teams) ? teams.filter((t) => t && t.teamId) : []
  const byId = new Map(list.map((t) => [t.teamId, t]))
  const children = new Map()
  const roots = []
  const orphans = []

  for (const t of list) {
    const parent = t.parentTeamId
    if (!parent) {
      roots.push(t)
    } else if (!byId.has(parent)) {
      orphans.push(t)
    } else {
      if (!children.has(parent)) children.set(parent, [])
      children.get(parent).push(t)
    }
  }

  const byTeamId = (a, b) => String(a.teamId).localeCompare(String(b.teamId))
  roots.sort(byTeamId)
  orphans.sort(byTeamId)
  for (const kids of children.values()) kids.sort(byTeamId)

  const rows = []
  const visiting = new Set()
  const visited = new Set()

  function walk(team, depth, orphan) {
    if (!team?.teamId || visited.has(team.teamId)) return
    if (visiting.has(team.teamId)) return
    visiting.add(team.teamId)
    rows.push({
      teamId: team.teamId,
      displayName: team.displayName ?? null,
      orgId: team.orgId ?? null,
      parentTeamId: team.parentTeamId ?? null,
      depth,
      orphan: !!orphan,
    })
    visited.add(team.teamId)
    for (const child of children.get(team.teamId) || []) {
      walk(child, depth + 1, false)
    }
    visiting.delete(team.teamId)
  }

  for (const r of roots) walk(r, 0, false)
  for (const o of orphans) walk(o, 0, true)
  for (const t of list) {
    if (!visited.has(t.teamId)) walk(t, 0, false)
  }
  return rows
}
