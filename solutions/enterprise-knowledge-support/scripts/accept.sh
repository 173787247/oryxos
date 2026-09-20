#!/usr/bin/env bash
# #478：新环境离线验收——不需要真实 LLM Key。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
PACK="$ROOT/solutions/enterprise-knowledge-support"
WS="$PACK/workspace"

echo "[478-accept] checking pack files…"
required=(
  "$PACK/README.md"
  "$PACK/ACCEPTANCE.md"
  "$PACK/citation-policy.md"
  "$PACK/permissions.sample.yml"
  "$WS/agents/knowledge-support/AGENT.md"
  "$WS/agents/knowledge-support/skills/cite-and-answer.md"
  "$WS/agents/knowledge-support/skills/escalate-human.md"
  "$WS/knowledge/support-faq/KNOWLEDGE.md"
  "$WS/knowledge/support-faq/password-reset.md"
  "$WS/flows/knowledge-handoff.flow.md"
  "$WS/evals/knowledge-support-suite.json"
)
for f in "${required[@]}"; do
  [[ -f "$f" ]] || { echo "missing: $f" >&2; exit 1; }
done

if grep -E '^[[:space:]]*-[[:space:]]*(shell|exec|write_file)\b' \
  "$WS/agents/knowledge-support/AGENT.md" >/dev/null; then
  echo "AGENT.md must not declare shell/exec/write_file" >&2
  exit 1
fi

echo "[478-accept] running eval + flow pack tests…"
cd "$ROOT"
mvn -B -pl oryxos-core -Dtest=EnterpriseKnowledgeSupportPackTest,FlowDocumentsTest test

echo "[478-accept] OK — citation suite + handoff flow validated"
