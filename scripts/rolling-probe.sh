#!/usr/bin/env bash
# 039 US2：滚动升级期间的连续可用性探测——固定速率打请求并记录每次结果，
# 结束后汇总非预期失败数（SC-002 零失败口径：非 2xx 且非 429 计为失败）。
# 用法：scripts/rolling-probe.sh <base-url> [interval-seconds] [duration-seconds]
# 例：  scripts/rolling-probe.sh http://localhost:18080 1 120
# Ctrl-C 或 duration 到点后输出统计并以失败数为退出码要素（0 失败 → exit 0）。

set -uo pipefail

BASE="${1:?用法: rolling-probe.sh <base-url> [interval] [duration]}"
INTERVAL="${2:-1}"
DURATION="${3:-120}"

total=0; ok=0; backpressure=0; fail=0
deadline=$((SECONDS + DURATION))
trap 'summarize' EXIT

summarize() {
  echo ""
  echo "== rolling-probe 统计 =="
  echo "total=${total} ok=${ok} backpressure429=${backpressure} unexpected_fail=${fail}"
  if [ "${fail}" -eq 0 ]; then
    echo "✅ SC-002：零非预期失败"
  else
    echo "❌ SC-002：存在 ${fail} 次非预期失败（见上方逐条记录）"
  fi
}

while [ "${SECONDS}" -lt "${deadline}" ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 45 "${BASE}/api/v1/health" || echo 000)
  total=$((total + 1))
  case "${code}" in
    2*) ok=$((ok + 1)) ;;
    429) backpressure=$((backpressure + 1)) ;;
    *)
      fail=$((fail + 1))
      echo "[$(date +%H:%M:%S)] FAIL http=${code}"
      ;;
  esac
  sleep "${INTERVAL}"
done

[ "${fail}" -eq 0 ]
