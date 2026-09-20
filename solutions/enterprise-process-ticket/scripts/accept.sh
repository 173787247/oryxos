#!/usr/bin/env bash
# #479：新环境离线验收——不需要真实 LLM Key。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
PACK="$ROOT/solutions/enterprise-process-ticket"
WS="$PACK/workspace"

echo "[479-accept] checking pack files…"
required=(
  "$PACK/README.md"
  "$PACK/ACCEPTANCE.md"
  "$PACK/approval-policy.md"
  "$PACK/permissions.sample.yml"
  "$WS/agents/ticket-ops/AGENT.md"
  "$WS/agents/ticket-ops/skills/triage-ticket.md"
  "$WS/agents/ticket-ops/skills/request-approval.md"
  "$WS/knowledge/ticket-playbooks/KNOWLEDGE.md"
  "$WS/flows/ticket-approve-apply.flow.md"
  "$WS/evals/process-ticket-suite.json"
)
for f in "${required[@]}"; do
  [[ -f "$f" ]] || { echo "missing: $f" >&2; exit 1; }
done

if grep -E '^[[:space:]]*-[[:space:]]*(shell|exec|write_file|ticket\.apply_change|ticket\.rollback)\b' \
  "$WS/agents/ticket-ops/AGENT.md" >/dev/null; then
  echo "AGENT.md must not declare shell/exec/write_file/ticket.apply_change/ticket.rollback" >&2
  exit 1
fi

if ! grep -q 'compensate:[[:space:]]*rollback' "$WS/flows/ticket-approve-apply.flow.md"; then
  echo "ticket-approve-apply.flow.md must declare apply.compensate: rollback" >&2
  exit 1
fi

if ! grep -q 'type:[[:space:]]*human' "$WS/flows/ticket-approve-apply.flow.md"; then
  echo "ticket-approve-apply.flow.md must include a human approval node" >&2
  exit 1
fi

echo "[479-accept] running eval + flow pack tests…"
cd "$ROOT"
mvn -B -pl oryxos-core -Dtest=EnterpriseProcessTicketPackTest,FlowDocumentsTest test

echo "[479-accept] OK — approval gate + compensate + timeline replay validated"
