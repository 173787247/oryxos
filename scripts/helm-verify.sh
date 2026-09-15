#!/usr/bin/env bash
# 039 门禁档：helm lint + template 渲染断言 + kubeconform schema 校验（无集群全自动）。
# 断言契约见 specs/039-k8s-delivery/contracts/helm-values.md。

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
export PATH="${HOME}/bin:${PATH}"

CHART=charts/oryxos
TEST_VALUES=${CHART}/ci/default-values-test.yaml
OUT="$(mktemp -d)/rendered.yaml"

fail() { echo "❌ $1" >&2; exit 1; }
pass() { echo "✅ $1"; }

# 1. lint
helm lint "${CHART}" -f "${TEST_VALUES}" >/dev/null || fail "helm lint 未通过"
pass "helm lint"

# 2. template 渲染 + kubeconform（K8s 1.27 schema，严格模式）
helm template oryxos "${CHART}" -f "${TEST_VALUES}" > "${OUT}"
kubeconform -strict -kubernetes-version 1.27.0 -summary "${OUT}" || fail "kubeconform 校验未通过"
pass "kubeconform"

# 3. 渲染产物断言
grep -q 'replicas: 2' "${OUT}" || fail "默认副本数应为 2"
grep -q 'path: /actuator/health/liveness' "${OUT}" || fail "缺 liveness 探针路径"
grep -q 'path: /actuator/health/readiness' "${OUT}" || fail "缺 readiness 探针路径"
grep -q 'terminationGracePeriodSeconds: 40' "${OUT}" || fail "优雅宽限应为 40s（compose 同口径）"
grep -q 'preStop' "${OUT}" || fail "缺 preStop"
grep -q 'maxUnavailable: 0' "${OUT}" || fail "滚动策略应 maxUnavailable=0"
grep -q 'ReadWriteMany' "${OUT}" || fail "多副本工作区必须 RWX"
grep -q 'secretRef' "${OUT}" || fail "凭证应经 Secret 注入"
grep -q 'enabled: true' "${OUT}" || fail "集群档应默认开启"
grep -q 'timeout-per-shutdown-phase: 30s' "${OUT}" || fail "drainTimeout 应注入 30s"
pass "渲染产物结构断言"

# 4. 零明文纪律：test values 未直填凭证时，渲染产物不得出现凭证键的明文值形态
if grep -qE 'SPRING_DATASOURCE_PASSWORD: .+|ORYXOS_MASTER_KEY: .+' "${OUT}"; then
  fail "渲染产物疑似含明文凭证（existingSecret 模式不应渲染 Secret 数据）"
fi
pass "零明文凭证"

# 5. 缺必填 values 须渲染失败并带指引
if helm template oryxos "${CHART}" >/dev/null 2>"${OUT}.err"; then
  fail "缺必填 values 时应渲染失败（database/masterKey）"
fi
grep -q 'K8sDeployGuide' "${OUT}.err" || fail "缺必填的报错应指向 K8sDeployGuide"
pass "缺必填 fail-fast 指引"

# 6. 多副本 + 非 RWX 须 fail 并指向 SharedVolumeGuide
if helm template oryxos "${CHART}" -f "${TEST_VALUES}" --set workspace.accessMode=ReadWriteOnce >/dev/null 2>"${OUT}.err2"; then
  fail "replicaCount>1 且非 RWX 时应渲染失败"
fi
grep -q 'SharedVolumeGuide' "${OUT}.err2" || fail "RWX 报错应指向 SharedVolumeGuide"
pass "RWX 模板断言"

# 7. 单副本 + RWO 合法
helm template oryxos "${CHART}" -f "${TEST_VALUES}" --set replicaCount=1 --set workspace.accessMode=ReadWriteOnce >/dev/null \
  || fail "单副本 RWO 应为合法配置"
pass "单副本 RWO 合法"

echo "helm-verify 全部通过"
