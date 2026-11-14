#!/usr/bin/env bash
# `make k8s-storm`: a storm of fault records on the kind cluster.
# Starts the fault-storm Job (infra/k8s/jobs/fault-storm.yaml), streams the
# worker pods for STORM_WATCH_SECONDS, then prints the replica count of every
# worker and of the api. Expected: only worker-fault goes above 1 (bulkhead +
# KEDA scaling on filegrid.jobs.fault).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CTX="kind-${K8S_CLUSTER:-filegrid}"
NS="filegrid"
WATCH_SECONDS="${STORM_WATCH_SECONDS:-180}"
k() { kubectl --context "$CTX" "$@"; }

replicas() {
  local d
  printf '%-18s %s\n' DEPLOYMENT READY/DESIRED
  for d in worker-fault worker-meter worker-document worker-video api; do
    printf '%-18s %s/%s\n' "$d" \
      "$(k -n "$NS" get deployment "$d" -o jsonpath='{.status.readyReplicas}')" \
      "$(k -n "$NS" get deployment "$d" -o jsonpath='{.spec.replicas}')"
  done
}

echo "==> before the storm"
replicas

# A finished Job cannot be re-run: delete the previous one first.
k -n "$NS" delete job fault-storm --ignore-not-found --wait=true
k apply -f "$ROOT/infra/k8s/jobs/fault-storm.yaml"

echo
echo "==> watching worker pods for ${WATCH_SECONDS}s (Ctrl+C stops only the watch)"
timeout "$WATCH_SECONDS" kubectl --context "$CTX" -n "$NS" get pods \
  -l app.kubernetes.io/component=worker -w || true

echo
echo "==> queue-driven scaling (KEDA)"
k -n "$NS" get scaledobject
k -n "$NS" get hpa
echo
echo "==> after the storm"
replicas
echo
echo "Storm job logs: kubectl -n $NS logs job/fault-storm --all-containers --prefix"
echo "worker-fault scales back to 1 about cooldownPeriod (60s) after the queue empties."
