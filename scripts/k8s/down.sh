#!/usr/bin/env bash
# `make k8s-down`: deletes the kind cluster (and every volume inside it).
# Docker Compose containers and volumes are not touched. The generated
# Secrets/ConfigMaps stay in infra/k8s/overlays/kind/generated/ (git-ignored).
set -euo pipefail

CLUSTER="${K8S_CLUSTER:-filegrid}"
command -v kind >/dev/null 2>&1 || { echo "k8s-down: kind not found" >&2; exit 1; }

if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  kind delete cluster --name "$CLUSTER"
else
  echo "k8s-down: no kind cluster named '$CLUSTER'"
fi
