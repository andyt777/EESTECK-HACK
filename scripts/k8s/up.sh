#!/usr/bin/env bash
# `make k8s-up`: FileGrid on a local kind cluster (Stage 19).
#   1. kind cluster `filegrid` (single node, infra/k8s/kind/cluster.yaml)
#   2. ingress-nginx, metrics-server (for the api HPA) and KEDA - pinned versions
#   3. build the local images with docker compose and load them into the node
#   4. generate Secrets/ConfigMaps from .env and apply the kind overlay
#   5. wait for the core deployments
#
# Docker Compose stays the main "one command" path; this is the scaling demo.
# Re-running is safe (every step is idempotent). Skip the image build with
# K8S_SKIP_BUILD=1 when the images are already up to date.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLUSTER="${K8S_CLUSTER:-filegrid}"
NS="filegrid"

# Pinned versions (checked against the upstream release pages, 2026-11):
INGRESS_NGINX_REF="controller-v1.15.1"   # kubernetes/ingress-nginx, kind provider manifest
METRICS_SERVER_VERSION="v0.9.1"          # kubernetes-sigs/metrics-server
KEDA_VERSION="2.21.0"                    # kedacore/keda, tested with Kubernetes 1.34-1.36

IMAGES=(filegrid/backend:local filegrid/frontend:local filegrid/intelligence:local filegrid/simulator:local)

die() { echo "k8s-up: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

for tool in docker kind kubectl; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool not found (see infra/k8s/README.md - prerequisites)"
done
[ -f "$ROOT/.env" ] || bash "$ROOT/scripts/init-env.sh"

cd "$ROOT"

step "kind cluster '$CLUSTER'"
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  echo "cluster already exists"
else
  kind create cluster --name "$CLUSTER" --config infra/k8s/kind/cluster.yaml
fi
CTX="kind-$CLUSTER"
k() { kubectl --context "$CTX" "$@"; }

step "ingress-nginx ($INGRESS_NGINX_REF)"
k apply -f "https://raw.githubusercontent.com/kubernetes/ingress-nginx/${INGRESS_NGINX_REF}/deploy/static/provider/kind/deploy.yaml"

step "metrics-server ($METRICS_SERVER_VERSION)"
k apply -f "https://github.com/kubernetes-sigs/metrics-server/releases/download/${METRICS_SERVER_VERSION}/components.yaml"
# kind kubelets use self-signed serving certificates.
if ! k -n kube-system get deployment metrics-server -o jsonpath='{.spec.template.spec.containers[0].args}' | grep -q kubelet-insecure-tls; then
  k -n kube-system patch deployment metrics-server --type=json \
    -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
fi

step "KEDA $KEDA_VERSION"
k apply --server-side -f "https://github.com/kedacore/keda/releases/download/v${KEDA_VERSION}/keda-${KEDA_VERSION}.yaml"

if [ "${K8S_SKIP_BUILD:-0}" != "1" ]; then
  step "build images (docker compose build)"
  docker compose build api frontend intelligence simulator
fi

step "load images into the kind node"
for image in "${IMAGES[@]}"; do
  docker image inspect "$image" >/dev/null 2>&1 || die "image $image missing - run without K8S_SKIP_BUILD"
  kind load docker-image "$image" --name "$CLUSTER"
done

step "wait for ingress-nginx and KEDA"
k wait -n ingress-nginx --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller --timeout=300s
k wait -n keda --for=condition=available deployment --all --timeout=300s

step "Secrets and ConfigMaps from .env (git-ignored)"
bash scripts/k8s/generate.sh
k create namespace "$NS" --dry-run=client -o yaml | k apply -f -
# Server-side apply: no last-applied annotation (the docs ConfigMap is large).
k apply --server-side --force-conflicts -f infra/k8s/overlays/kind/generated/

step "FileGrid manifests (kustomize overlay kind)"
k apply -k infra/k8s/overlays/kind

step "wait for FileGrid (the first start runs the Flyway migrations)"
for sts in meta-db store-db s3 ftp rabbitmq; do
  k -n "$NS" rollout status "statefulset/$sts" --timeout=300s
done
for deploy in api worker-meter worker-fault worker-document worker-video frontend intelligence simulator; do
  k -n "$NS" rollout status "deployment/$deploy" --timeout=600s
done

echo
echo "FileGrid on kind is up:  https://localhost:9443   (self-signed certificate)"
echo "Health:                  curl -k https://localhost:9443/api/actuator/health"
echo "Scaling demo:            make k8s-storm   (in another terminal: kubectl -n $NS get pods -w)"
