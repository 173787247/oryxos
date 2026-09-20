#!/usr/bin/env bash
# #480：新环境离线验收——不需要真实 LLM Key。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
PACK="$ROOT/solutions/enterprise-controlled-rdops"
WS="$PACK/workspace"

echo "[480-accept] checking pack files…"
required=(
  "$PACK/README.md"
  "$PACK/ACCEPTANCE.md"
  "$PACK/audit-policy.md"
  "$PACK/permissions.sample.yml"
  "$WS/agents/rdops-controlled/AGENT.md"
  "$WS/agents/rdops-controlled/skills/draft-change.md"
  "$WS/agents/rdops-controlled/skills/request-ops-approval.md"
  "$WS/knowledge/rdops-playbooks/KNOWLEDGE.md"
  "$WS/flows/rdops-approve-exec.flow.md"
  "$WS/evals/controlled-rdops-suite.json"
)
for f in "${required[@]}"; do
  [[ -f "$f" ]] || { echo "missing: $f" >&2; exit 1; }
done

if grep -E '^[[:space:]]*-[[:space:]]*(shell|exec|write_file|ops\.exec|ops\.deploy)\b' \
  "$WS/agents/rdops-controlled/AGENT.md" >/dev/null; then
  echo "AGENT.md must not declare shell/exec/write_file/ops.exec/ops.deploy" >&2
  exit 1
fi

if ! grep -q 'from:[[:space:]]*review\.approved_params' "$WS/flows/rdops-approve-exec.flow.md"; then
  echo "rdops-approve-exec.flow.md must bind apply from review.approved_params" >&2
  exit 1
fi

# extract apply node block until next top-level node key
apply_block=$(awk '/^  apply:/{flag=1;next}/^  [a-z]/{if(flag){exit}}flag' "$WS/flows/rdops-approve-exec.flow.md")
if echo "$apply_block" | grep -q 'from:[[:space:]]*prepare\.plan'; then
  echo "apply must not take params from prepare.plan" >&2
  exit 1
fi
if ! echo "$apply_block" | grep -q 'from:[[:space:]]*review\.approved_params'; then
  echo "apply params must come from review.approved_params" >&2
  exit 1
fi

if ! grep -q 'type:[[:space:]]*human' "$WS/flows/rdops-approve-exec.flow.md"; then
  echo "rdops-approve-exec.flow.md must include a human approval node" >&2
  exit 1
fi

if ! grep -q 'pattern:[[:space:]]*shell' "$PACK/permissions.sample.yml"; then
  echo "permissions.sample.yml must GLOBAL_DENY shell" >&2
  exit 1
fi

echo "[480-accept] running eval + flow pack tests…"
cd "$ROOT"
mvn -B -pl oryxos-core -Dtest=EnterpriseControlledRdopsPackTest,FlowDocumentsTest test

echo "[480-accept] OK — default deny + approved params + audit validated"
