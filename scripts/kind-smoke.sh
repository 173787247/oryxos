#!/usr/bin/env bash
# 039 安装冒烟（analyze C1，CI 与本机共用）：kind 集群上 helm install 双副本 →
# 就绪 + 实例双活 + 跨副本可见性（SC-001 自动化面）。
# 用法：scripts/kind-smoke.sh <image:tag> [kind-cluster-name]
# 依赖：kind/kubectl/helm（scripts/install-k8s-tools.sh all）+ docker；单节点 kind 用
# hostPath 静态 PV 提供 RWX（同节点天然满足读写可见 + rename 原子；真多机 RWX 见 SharedVolumeGuide）。

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
export PATH="${HOME}/bin:${PATH}"

IMAGE="${1:?用法: kind-smoke.sh <image:tag> [cluster]}"
CLUSTER="${2:-oryxos-ci}"
NS=oryxos-smoke
RELEASE=oryxos

echo "== 载入镜像 ${IMAGE} 到 kind/${CLUSTER} =="
kind load docker-image "${IMAGE}" --name "${CLUSTER}"

kubectl create namespace "${NS}" --dry-run=client -o yaml | kubectl apply -f -

echo "== 附带 PostgreSQL（共享事实源，025/026 前提） =="
kubectl -n "${NS}" apply -f - <<'EOF'
apiVersion: v1
kind: Pod
metadata:
  name: pg
  labels: { app: pg }
spec:
  containers:
    - name: pg
      image: postgres:16-alpine
      env:
        - { name: POSTGRES_PASSWORD, value: smoke-pass }
      ports: [ { containerPort: 5432 } ]
      readinessProbe:
        exec: { command: ["pg_isready", "-U", "postgres"] }
        periodSeconds: 2
---
apiVersion: v1
kind: Service
metadata:
  name: pg
spec:
  selector: { app: pg }
  ports: [ { port: 5432 } ]
EOF
kubectl -n "${NS}" wait --for=condition=Ready pod/pg --timeout=120s

echo "== 密文（两项必填，引用式） =="
kubectl -n "${NS}" create secret generic oryxos-db \
  --from-literal=SPRING_DATASOURCE_URL="jdbc:postgresql://pg:5432/postgres" \
  --from-literal=SPRING_DATASOURCE_USERNAME=postgres \
  --from-literal=SPRING_DATASOURCE_PASSWORD=smoke-pass \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "${NS}" create secret generic oryxos-master-key \
  --from-literal=ORYXOS_MASTER_KEY="$(openssl rand -base64 32)" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "== 单节点 RWX：hostPath 静态 PV（storageClassName=manual-rwx） =="
kubectl apply -f - <<EOF
apiVersion: v1
kind: PersistentVolume
metadata:
  name: oryxos-workspace-pv
spec:
  capacity: { storage: 5Gi }
  accessModes: [ ReadWriteMany ]
  storageClassName: manual-rwx
  hostPath: { path: /tmp/oryxos-workspace }
  persistentVolumeReclaimPolicy: Delete
EOF

echo "== helm install（一条命令，仅两项密文引用 + 冒烟覆写） =="
IMAGE_REPO="${IMAGE%:*}"
IMAGE_TAG="${IMAGE##*:}"
helm upgrade --install "${RELEASE}" charts/oryxos -n "${NS}" \
  -f charts/oryxos/ci/default-values-test.yaml \
  --set image.repository="${IMAGE_REPO}" \
  --set image.tag="${IMAGE_TAG}" \
  --set image.pullPolicy=Never \
  --set workspace.storageClassName=manual-rwx

echo "== 双副本就绪（SC-001：安装到就绪 ≤5min） =="
kubectl -n "${NS}" rollout status "deploy/${RELEASE}" --timeout=300s

echo "== 实例双活断言（026 可见性） =="
kubectl -n "${NS}" port-forward "svc/${RELEASE}" 18080:8080 >/dev/null 2>&1 &
PF_PID=$!
trap 'kill ${PF_PID} 2>/dev/null || true' EXIT
for i in $(seq 1 30); do
  curl -fs http://localhost:18080/api/v1/health >/dev/null 2>&1 && break
  sleep 1
done
INSTANCES=$(curl -fs http://localhost:18080/api/v1/instances)
echo "${INSTANCES}" | grep -q '"clusterEnabled":true' || { echo "❌ 集群档未开启"; exit 1; }
ALIVE=$(echo "${INSTANCES}" | grep -o '"alive":true' | wc -l)
[ "${ALIVE}" -ge 2 ] || { echo "❌ 存活副本 ${ALIVE} < 2：${INSTANCES}"; exit 1; }
echo "✅ 实例双活"

echo "== 跨副本可见性（027 on 共享卷）：经服务建 Agent，各 Pod 本地断言可见 =="
curl -fs -X POST http://localhost:18080/api/v1/agents \
  -H 'Content-Type: application/json' \
  -d '{"name":"smoke-agent","description":"kind smoke"}' >/dev/null
PODS=$(kubectl -n "${NS}" get pods -l "app.kubernetes.io/instance=${RELEASE}" -o name)
for pod in ${PODS}; do
  ok=""
  for i in $(seq 1 10); do
    if kubectl -n "${NS}" exec "${pod}" -- curl -fs http://localhost:8080/api/v1/agents 2>/dev/null | grep -q smoke-agent; then
      ok=1; break
    fi
    sleep 1
  done
  [ -n "${ok}" ] || { echo "❌ ${pod} 10s 内未见 smoke-agent（027 可见性）"; exit 1; }
done
echo "✅ 跨副本 ≤3s 量级可见（各 Pod 本地确认）"

echo "kind-smoke 全部通过"
