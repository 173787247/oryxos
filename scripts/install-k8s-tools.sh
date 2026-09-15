#!/usr/bin/env bash
# 039：按需安装 K8s 工具链到 ~/bin（用户目录，无 sudo）。幂等：已存在且可运行则跳过。
# 门禁档必需：helm、kubeconform；真机档另需：kubectl、kind（kind 依赖 docker，见 quickstart V0 环境阶梯）。
# 用法：scripts/install-k8s-tools.sh [gate|all]   （默认 gate）

set -euo pipefail

MODE="${1:-gate}"
BIN_DIR="${HOME}/bin"
mkdir -p "${BIN_DIR}"

HELM_VERSION="v3.16.4"
KUBECONFORM_VERSION="v0.6.7"
KUBECTL_VERSION="v1.31.4"
KIND_VERSION="v0.26.0"

ARCH="$(uname -m)"
case "${ARCH}" in
  x86_64) ARCH=amd64 ;;
  aarch64|arm64) ARCH=arm64 ;;
  *) echo "不支持的架构: ${ARCH}" >&2; exit 1 ;;
esac

have() { command -v "$1" >/dev/null 2>&1; }

install_helm() {
  if have helm; then echo "helm 已存在: $(helm version --short)"; return; fi
  echo "安装 helm ${HELM_VERSION} → ${BIN_DIR}"
  curl -fsSL "https://get.helm.sh/helm-${HELM_VERSION}-linux-${ARCH}.tar.gz" \
    | tar -xz -C /tmp "linux-${ARCH}/helm"
  mv "/tmp/linux-${ARCH}/helm" "${BIN_DIR}/helm" && chmod +x "${BIN_DIR}/helm"
  rmdir "/tmp/linux-${ARCH}" 2>/dev/null || true
}

install_kubeconform() {
  if have kubeconform; then echo "kubeconform 已存在: $(kubeconform -v)"; return; fi
  echo "安装 kubeconform ${KUBECONFORM_VERSION} → ${BIN_DIR}"
  curl -fsSL "https://github.com/yannh/kubeconform/releases/download/${KUBECONFORM_VERSION}/kubeconform-linux-${ARCH}.tar.gz" \
    | tar -xz -C "${BIN_DIR}" kubeconform
  chmod +x "${BIN_DIR}/kubeconform"
}

install_kubectl() {
  if have kubectl; then echo "kubectl 已存在: $(kubectl version --client 2>/dev/null | head -1)"; return; fi
  echo "安装 kubectl ${KUBECTL_VERSION} → ${BIN_DIR}"
  curl -fsSL -o "${BIN_DIR}/kubectl" \
    "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${ARCH}/kubectl"
  chmod +x "${BIN_DIR}/kubectl"
}

install_kind() {
  if have kind; then echo "kind 已存在: $(kind version)"; return; fi
  echo "安装 kind ${KIND_VERSION} → ${BIN_DIR}"
  curl -fsSL -o "${BIN_DIR}/kind" \
    "https://kind.sigs.k8s.io/dl/${KIND_VERSION}/kind-linux-${ARCH}"
  chmod +x "${BIN_DIR}/kind"
}

export PATH="${BIN_DIR}:${PATH}"
install_helm
install_kubeconform
if [ "${MODE}" = "all" ]; then
  install_kubectl
  install_kind
  have docker || echo "⚠️ 未检测到 docker：kind 需要容器运行时（Docker Desktop WSL integration / docker-ce），见 specs/039-k8s-delivery/quickstart.md V0"
fi
echo "完成。请确保 ~/bin 在 PATH（当前会话已临时生效）。"
