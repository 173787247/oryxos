# Acceptance report — 041 asset governance first cut

**Status**: first cut on `feat/041-asset-governance` for #463.

## Met

- [x] Flag default off (`oryxos.web.asset-governance.enabled=false`)
- [x] `GOVERNANCE.yml` sidecar for agents / skills / knowledge
- [x] `AssetAwareAuthorizationServiceImpl` decorator reuses `AuthorizationService.decide` only
- [x] OFFLINE deny + PRIVATE owner/ADMIN gate (USER); API_KEY only OFFLINE
- [x] Bind/invoke extra decide via `AssetBindGuard`
- [x] GET/PUT governance APIs + V11 `asset_governance_events`
- [x] Unit tests: flag off passthrough, OFFLINE/PRIVATE, store roundtrip, guard
- [x] Inbound OFFLINE gate (`InboundAssetGovernanceGate` in `InboundMessageService`; challenges stay in adapters)
- [x] Admin UI: Agent / Skill / Knowledge detail「治理」panels → `GET/PUT /api/v1/{agents|skills|knowledge}/{name}/governance`
- [x] Admin UI: 入站渠道 list + governance panel → `GET/PUT /api/v1/channels/{name}/governance`
- [x] Catalog list filter via `AssetBindGuard.isVisible` → `decide(READ_WORKSPACE, named resource)` on agents/skills/knowledge/channels lists
- [x] WORKSPACE `teamOwner` gate behind `workspace-team-acl-enabled` (default off); OIDC groups → session `Principal.teamIds`

## Honest gaps

- No durable teams/orgs/members tables or JIT team provisioning
- Team ids are session-scoped (OIDC groups cache); Basic Auth sessions have empty teamIds
- No full version history
- `/skills/catalog` 012 author visibility not merged with GOVERNANCE.yml
