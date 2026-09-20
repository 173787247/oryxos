#!/usr/bin/env bash
# #472 / 048: CI-friendly regression gate. Runs the eval Surefire tests that assert
# sample suite passes and regressing suite is blocked. Exit non-zero on failure.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
echo "[eval-gate] running oryxos-core eval tests…"
mvn -B -pl oryxos-core -Dtest=EvalHarnessTest,EvalRegressionGateTest,EvalFixtureLoaderTest test
echo "[eval-gate] OK"
