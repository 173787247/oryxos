#!/usr/bin/env bash
# 039 US4（026 留账复测）：独立发压——N 并发独立会话打 /api/v1/agents/{agent}/invoke，
# 统计吞吐（req/s）。发压端应与服务副本不同机/不同资源组；服务端配 -Doryxos.mock.latency-ms
# 模拟真实 LLM 往返（否则瓶颈落在发压端，重蹈 026 口径缺陷）。
# 用法：scripts/load-test.sh <base-url> [concurrency] [requests-per-worker] [agent]
# 例：  scripts/load-test.sh http://localhost:18080 64 20 default

set -euo pipefail

BASE="${1:?用法: load-test.sh <base-url> [concurrency] [per-worker] [agent]}"
CONCURRENCY="${2:-64}"
PER_WORKER="${3:-20}"
AGENT="${4:-default}"

TMP="$(mktemp -d)"
echo "并发=${CONCURRENCY} 每工人=${PER_WORKER} 总请求=$((CONCURRENCY * PER_WORKER)) → ${BASE}"

worker() {
  local id=$1 okc=0
  for i in $(seq 1 "${PER_WORKER}"); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 120 \
      -X POST "${BASE}/api/v1/agents/${AGENT}/invoke" \
      -H 'Content-Type: application/json' \
      -d "{\"message\":\"load-${id}-${i}\"}") || code=000
    [ "${code}" = "200" ] && okc=$((okc + 1))
  done
  echo "${okc}" > "${TMP}/w${id}"
}

begin=$(date +%s.%N)
for w in $(seq 1 "${CONCURRENCY}"); do
  worker "${w}" &
done
wait
end=$(date +%s.%N)

elapsed=$(awk "BEGIN{printf \"%.2f\", ${end}-${begin}}")
ok=$(cat "${TMP}"/w* | awk '{s+=$1} END{print s}')
total=$((CONCURRENCY * PER_WORKER))
tps=$(awk "BEGIN{printf \"%.1f\", ${ok}/${elapsed}}")
echo "== load-test 统计 =="
echo "elapsed=${elapsed}s ok=${ok}/${total} throughput=${tps} req/s"
rm -rf "${TMP}"
